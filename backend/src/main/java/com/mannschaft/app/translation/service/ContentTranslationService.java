package com.mannschaft.app.translation.service;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.PagedResponse.PageMeta;
import com.mannschaft.app.translation.TranslationErrorCode;
import com.mannschaft.app.translation.TranslationStatus;
import com.mannschaft.app.translation.entity.ContentTranslationEntity;
import com.mannschaft.app.translation.entity.TranslationConfigEntity;
import com.mannschaft.app.translation.repository.ContentTranslationQueryRepository;
import com.mannschaft.app.translation.repository.ContentTranslationRepository;
import com.mannschaft.app.translation.repository.TranslationConfigRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 翻訳コンテンツ管理サービス。
 * 翻訳の作成・取得・更新・ステータス変更・公開・論理削除を担う。
 * また原文更新時の一括ステータス更新（markAsStale）およびダッシュボード統計を提供する。
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ContentTranslationService {

    private final ContentTranslationRepository contentTranslationRepository;
    private final ContentTranslationQueryRepository contentTranslationQueryRepository;
    private final TranslationConfigRepository translationConfigRepository;

    // ========================================
    // リクエスト DTO
    // ========================================

    /**
     * 翻訳コンテンツ作成リクエスト。
     */
    @Getter
    @Setter
    public static class CreateTranslationRequest {
        /** スコープ種別（ORGANIZATION / TEAM） */
        private String scopeType;
        /** スコープID */
        private Long scopeId;
        /** 原文コンテンツ種別（BLOG_POST / ANNOUNCEMENT / KNOWLEDGE_BASE） */
        @NotBlank
        private String contentType;
        /** 原文コンテンツID */
        @NotNull
        private Long contentId;
        /** 翻訳先言語コード（ISO 639-1） */
        @NotBlank
        private String language;
        /** 翻訳済みタイトル */
        private String title;
        /** 翻訳済み本文（Markdown形式） */
        private String body;
        /** 翻訳済み要約・抜粋（任意） */
        private String summary;
        /**
         * 翻訳作成時点の原文 updated_at スナップショット。
         * 翻訳者が作業を開始した原文のバージョンを記録し、原文更新検知に使用する。
         */
        private LocalDateTime sourceUpdatedAt;
    }

    /**
     * 翻訳コンテンツ更新リクエスト。
     */
    @Getter
    @Setter
    public static class UpdateTranslationRequest {
        /** 翻訳済みタイトル（nullの場合は変更なし） */
        private String title;
        /** 翻訳済み本文（nullの場合は変更なし） */
        private String body;
        /** 翻訳済み要約・抜粋（nullの場合は変更なし） */
        private String summary;
        /** 楽観的ロック用バージョン */
        @NotNull
        private Long version;
    }

    /**
     * ステータス変更リクエスト。
     */
    @Getter
    @Setter
    public static class ChangeStatusRequest {
        /** 遷移先ステータス */
        @NotBlank
        private String status;
        /** 楽観的ロック用バージョン */
        @NotNull
        private Long version;
    }

    // ========================================
    // レスポンス DTO
    // ========================================

    /**
     * 翻訳コンテンツ詳細レスポンス。
     */
    @Getter
    public static class ContentTranslationResponse {
        private final Long id;
        private final String scopeType;
        private final Long scopeId;
        private final String contentType;
        private final Long contentId;
        private final String language;
        private final String title;
        private final String body;
        private final String summary;
        private final String status;
        private final Long translatorId;
        private final Long reviewerId;
        private final LocalDateTime sourceUpdatedAt;
        private final LocalDateTime publishedAt;
        private final LocalDateTime createdAt;
        private final LocalDateTime updatedAt;
        private final Long version;

        public ContentTranslationResponse(ContentTranslationEntity entity) {
            this.id = entity.getId();
            this.scopeType = entity.getScopeType();
            this.scopeId = entity.getScopeId();
            this.contentType = entity.getSourceType();
            this.contentId = entity.getSourceId();
            this.language = entity.getLanguage();
            this.title = entity.getTranslatedTitle();
            this.body = entity.getTranslatedBody();
            this.summary = entity.getTranslatedExcerpt();
            this.status = entity.getStatus();
            this.translatorId = entity.getTranslatorId();
            this.reviewerId = entity.getReviewerId();
            this.sourceUpdatedAt = entity.getSourceUpdatedAt();
            this.publishedAt = entity.getPublishedAt();
            this.createdAt = entity.getCreatedAt();
            this.updatedAt = entity.getUpdatedAt();
            this.version = entity.getVersion();
        }
    }

    /**
     * 翻訳コンテンツ一覧用サマリーレスポンス。
     */
    @Getter
    public static class TranslationSummaryResponse {
        private final Long id;
        private final String contentType;
        private final Long contentId;
        private final String language;
        private final String translatedTitle;
        private final String status;
        private final Long translatorId;
        private final LocalDateTime sourceUpdatedAt;
        private final LocalDateTime publishedAt;
        private final LocalDateTime updatedAt;

        public TranslationSummaryResponse(ContentTranslationEntity entity) {
            this.id = entity.getId();
            this.contentType = entity.getSourceType();
            this.contentId = entity.getSourceId();
            this.language = entity.getLanguage();
            this.translatedTitle = entity.getTranslatedTitle();
            this.status = entity.getStatus();
            this.translatorId = entity.getTranslatorId();
            this.sourceUpdatedAt = entity.getSourceUpdatedAt();
            this.publishedAt = entity.getPublishedAt();
            this.updatedAt = entity.getUpdatedAt();
        }
    }

    /**
     * 翻訳ダッシュボードレスポンス。
     */
    @Getter
    public static class TranslationDashboardResponse {
        private final long totalTranslations;
        private final long draft;
        private final long inReview;
        private final long published;
        private final long needsUpdate;

        public TranslationDashboardResponse(Map<String, Long> statusCounts) {
            this.draft = statusCounts.getOrDefault("DRAFT", 0L);
            this.inReview = statusCounts.getOrDefault("IN_REVIEW", 0L);
            this.published = statusCounts.getOrDefault("PUBLISHED", 0L);
            this.needsUpdate = statusCounts.getOrDefault("NEEDS_UPDATE", 0L);
            this.totalTranslations = this.draft + this.inReview + this.published + this.needsUpdate;
        }
    }

    // ========================================
    // 公開メソッド
    // ========================================

    /**
     * 翻訳コンテンツを作成する。
     * 同一contentType+contentId+languageの翻訳が既に存在する場合はエラー。
     * 指定言語がスコープの translation_configs.enabled_languages に含まれない場合はエラー。
     * ステータスは DRAFT で作成する。
     *
     * @param createdBy 作成者ユーザーID（翻訳者として記録）
     * @param req       作成リクエスト
     * @return 作成した翻訳コンテンツのレスポンス
     */
    @Transactional
    public ApiResponse<ContentTranslationResponse> createTranslation(
            Long createdBy, CreateTranslationRequest req) {

        // 言語有効チェック
        validateLanguageEnabled(req.getScopeType(), req.getScopeId(), req.getLanguage());

        // 同一原文×言語の重複チェック（論理削除済みは除外）
        Optional<ContentTranslationEntity> duplicate =
                contentTranslationRepository.findBySourceTypeAndSourceIdAndLanguageAndDeletedAtIsNull(
                        req.getContentType(), req.getContentId(), req.getLanguage());
        if (duplicate.isPresent()) {
            throw new BusinessException(TranslationErrorCode.TRANSLATION_004);
        }

        // sourceUpdatedAt が未設定の場合は現在時刻をデフォルトとして使用
        LocalDateTime sourceUpdatedAt = req.getSourceUpdatedAt() != null
                ? req.getSourceUpdatedAt()
                : LocalDateTime.now();

        ContentTranslationEntity entity = ContentTranslationEntity.builder()
                .scopeType(req.getScopeType())
                .scopeId(req.getScopeId())
                .sourceType(req.getContentType())
                .sourceId(req.getContentId())
                .language(req.getLanguage())
                .translatedTitle(req.getTitle())
                .translatedBody(req.getBody())
                .translatedExcerpt(req.getSummary())
                .status(TranslationStatus.DRAFT.name())
                .translatorId(createdBy)
                .sourceUpdatedAt(sourceUpdatedAt)
                .build();

        ContentTranslationEntity saved = contentTranslationRepository.save(entity);
        log.info("翻訳コンテンツ作成: id={}, sourceType={}, sourceId={}, language={}",
                saved.getId(), req.getContentType(), req.getContentId(), req.getLanguage());

        return ApiResponse.of(new ContentTranslationResponse(saved));
    }

    /**
     * 翻訳コンテンツをIDで取得する。
     *
     * @param id 翻訳コンテンツID
     * @return 翻訳コンテンツのレスポンス
     * @throws BusinessException TRANSLATION_002: 対象が見つからない場合
     */
    public ApiResponse<ContentTranslationResponse> getTranslation(Long id) {
        ContentTranslationEntity entity = findOrThrow(id);
        return ApiResponse.of(new ContentTranslationResponse(entity));
    }

    /**
     * 原文種別・原文ID・言語で翻訳コンテンツを取得する。
     *
     * @param contentType 原文コンテンツ種別
     * @param contentId   原文コンテンツID
     * @param language    言語コード
     * @return 翻訳コンテンツのレスポンス
     * @throws BusinessException TRANSLATION_002: 対象が見つからない場合
     */
    public ApiResponse<ContentTranslationResponse> getTranslationForContent(
            String contentType, Long contentId, String language) {

        ContentTranslationEntity entity =
                contentTranslationRepository.findBySourceTypeAndSourceIdAndLanguageAndDeletedAtIsNull(
                        contentType, contentId, language)
                        .orElseThrow(() -> new BusinessException(TranslationErrorCode.TRANSLATION_002));

        return ApiResponse.of(new ContentTranslationResponse(entity));
    }

    /**
     * 原文コンテンツの全翻訳一覧を取得する。
     *
     * @param contentType 原文コンテンツ種別
     * @param contentId   原文コンテンツID
     * @return 翻訳サマリーレスポンスのリスト
     */
    public ApiResponse<List<TranslationSummaryResponse>> listTranslationsForContent(
            String contentType, Long contentId) {

        List<ContentTranslationEntity> entities =
                contentTranslationRepository.findBySourceTypeAndSourceIdAndDeletedAtIsNull(
                        contentType, contentId);

        List<TranslationSummaryResponse> responses = entities.stream()
                .map(TranslationSummaryResponse::new)
                .collect(Collectors.toList());

        return ApiResponse.of(responses);
    }

    /**
     * スコープ内の翻訳コンテンツ一覧を取得する（フィルタ+ページネーション対応）。
     *
     * @param scopeType  スコープ種別
     * @param scopeId    スコープID
     * @param status     ステータスフィルタ（null許容）
     * @param language   言語フィルタ（null許容）
     * @param sourceType コンテンツ種別フィルタ（null許容）
     * @param page       ページ番号（0始まり）
     * @param size       1ページあたり件数
     * @return ページネーション付き翻訳サマリーレスポンス
     */
    public PagedResponse<TranslationSummaryResponse> listTranslations(
            String scopeType, Long scopeId,
            String status, String language, String sourceType,
            int page, int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "updatedAt"));

        Page<ContentTranslationEntity> entityPage =
                contentTranslationRepository.findByScope(
                        scopeType, scopeId, status, language, sourceType, pageable);

        List<TranslationSummaryResponse> responses = entityPage.getContent().stream()
                .map(TranslationSummaryResponse::new)
                .collect(Collectors.toList());

        PageMeta meta = new PageMeta(
                entityPage.getTotalElements(),
                entityPage.getNumber(),
                entityPage.getSize(),
                entityPage.getTotalPages());

        return PagedResponse.of(responses, meta);
    }

    /**
     * 翻訳コンテンツを更新する。楽観的ロックでバージョン競合を検出する。
     *
     * @param id        翻訳コンテンツID
     * @param updatedBy 更新者ユーザーID（ログ記録用）
     * @param req       更新リクエスト
     * @return 更新後の翻訳コンテンツのレスポンス
     * @throws BusinessException TRANSLATION_002: 対象が見つからない場合
     * @throws BusinessException TRANSLATION_007: バージョン不一致の場合
     */
    @Transactional
    public ApiResponse<ContentTranslationResponse> updateTranslation(
            Long id, Long updatedBy, UpdateTranslationRequest req) {

        ContentTranslationEntity entity = findOrThrow(id);

        // 楽観的ロック: バージョンチェック
        checkVersion(entity, req.getVersion());

        // 翻訳内容を更新（nullの場合は既存値を維持）
        String newTitle = req.getTitle() != null ? req.getTitle() : entity.getTranslatedTitle();
        String newBody = req.getBody() != null ? req.getBody() : entity.getTranslatedBody();
        String newSummary = req.getSummary() != null ? req.getSummary() : entity.getTranslatedExcerpt();

        entity.updateContent(newTitle, newBody, newSummary);

        try {
            ContentTranslationEntity saved = contentTranslationRepository.save(entity);
            log.info("翻訳コンテンツ更新: id={}, updatedBy={}", id, updatedBy);
            return ApiResponse.of(new ContentTranslationResponse(saved));
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new BusinessException(TranslationErrorCode.TRANSLATION_007);
        }
    }

    /**
     * 翻訳コンテンツのステータスを変更する。遷移ルールをバリデーションする。
     *
     * @param id  翻訳コンテンツID
     * @param req ステータス変更リクエスト
     * @return 更新後の翻訳コンテンツのレスポンス
     * @throws BusinessException TRANSLATION_002: 対象が見つからない場合
     * @throws BusinessException TRANSLATION_005: 不正なステータス遷移の場合
     * @throws BusinessException TRANSLATION_007: バージョン不一致の場合
     */
    @Transactional
    public ApiResponse<ContentTranslationResponse> changeStatus(Long id, ChangeStatusRequest req) {
        ContentTranslationEntity entity = findOrThrow(id);

        // 楽観的ロック: バージョンチェック
        checkVersion(entity, req.getVersion());

        TranslationStatus currentStatus = TranslationStatus.valueOf(entity.getStatus());
        TranslationStatus targetStatus;
        try {
            targetStatus = TranslationStatus.valueOf(req.getStatus());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(TranslationErrorCode.TRANSLATION_005);
        }

        // ステータス遷移バリデーション
        if (!currentStatus.canTransitionTo(targetStatus)) {
            throw new BusinessException(TranslationErrorCode.TRANSLATION_005);
        }

        // PUBLISHED への遷移時は publishedAt を記録
        if (targetStatus == TranslationStatus.PUBLISHED) {
            entity.publish();
        } else {
            entity.updateStatus(targetStatus.name());
        }

        try {
            ContentTranslationEntity saved = contentTranslationRepository.save(entity);
            log.info("翻訳ステータス変更: id={}, {} → {}", id, currentStatus, targetStatus);
            return ApiResponse.of(new ContentTranslationResponse(saved));
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new BusinessException(TranslationErrorCode.TRANSLATION_007);
        }
    }

    /**
     * 翻訳コンテンツを公開状態（PUBLISHED）に更新する。
     *
     * @param id 翻訳コンテンツID
     * @return 更新後の翻訳コンテンツのレスポンス
     * @throws BusinessException TRANSLATION_002: 対象が見つからない場合
     */
    @Transactional
    public ApiResponse<ContentTranslationResponse> publishTranslation(Long id) {
        ContentTranslationEntity entity = findOrThrow(id);
        entity.publish();
        ContentTranslationEntity saved = contentTranslationRepository.save(entity);
        log.info("翻訳コンテンツ公開: id={}", id);
        return ApiResponse.of(new ContentTranslationResponse(saved));
    }

    /**
     * 指定原文コンテンツのPUBLISHED翻訳を全てNEEDS_UPDATEに更新する。
     * 原文が更新された際に呼び出す（イベントリスナー・バッチからの呼び出し）。
     *
     * @param contentType 原文コンテンツ種別
     * @param contentId   原文コンテンツID
     * @return 更新件数
     */
    @Transactional
    public int markAsStale(String contentType, Long contentId) {
        // PUBLISHED状態の翻訳を全て取得
        List<ContentTranslationEntity> publishedList =
                contentTranslationRepository.findBySourceTypeAndSourceIdAndStatusAndDeletedAtIsNull(
                        contentType, contentId, TranslationStatus.PUBLISHED.name());

        int count = 0;
        for (ContentTranslationEntity entity : publishedList) {
            entity.updateStatus(TranslationStatus.NEEDS_UPDATE.name());
            contentTranslationRepository.save(entity);
            count++;
        }

        if (count > 0) {
            log.info("翻訳コンテンツをNEEDS_UPDATEに更新: contentType={}, contentId={}, 件数={}",
                    contentType, contentId, count);
        }
        return count;
    }

    /**
     * 翻訳コンテンツを論理削除する。
     *
     * @param id 翻訳コンテンツID
     * @throws BusinessException TRANSLATION_002: 対象が見つからない場合
     */
    @Transactional
    public void deleteTranslation(Long id) {
        ContentTranslationEntity entity = findOrThrow(id);
        entity.softDelete();
        contentTranslationRepository.save(entity);
        log.info("翻訳コンテンツ論理削除: id={}", id);
    }

    /**
     * 翻訳ダッシュボード統計を取得する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @return ダッシュボードレスポンス
     */
    public ApiResponse<TranslationDashboardResponse> getDashboard(String scopeType, Long scopeId) {
        Map<String, Long> statusCounts =
                contentTranslationQueryRepository.countByStatusGrouped(scopeType, scopeId);
        return ApiResponse.of(new TranslationDashboardResponse(statusCounts));
    }

    // ========================================
    // 内部メソッド
    // ========================================

    /**
     * IDで翻訳コンテンツを取得する。見つからない場合は TRANSLATION_002 例外をスロー。
     *
     * @param id 翻訳コンテンツID
     * @return 翻訳コンテンツエンティティ
     */
    public ContentTranslationEntity findOrThrow(Long id) {
        return contentTranslationRepository.findById(id)
                .orElseThrow(() -> new BusinessException(TranslationErrorCode.TRANSLATION_002));
    }

    /**
     * 楽観的ロックのバージョンチェック。
     *
     * @param entity         エンティティ
     * @param requestVersion リクエストのバージョン
     * @throws BusinessException TRANSLATION_007: バージョン不一致の場合
     */
    private void checkVersion(ContentTranslationEntity entity, Long requestVersion) {
        if (!entity.getVersion().equals(requestVersion)) {
            throw new BusinessException(TranslationErrorCode.TRANSLATION_007);
        }
    }

    /**
     * 指定言語がスコープの翻訳設定で有効化されているか検証する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param language  言語コード
     * @throws BusinessException TRANSLATION_003: 言語が有効化されていない場合
     */
    private void validateLanguageEnabled(String scopeType, Long scopeId, String language) {
        Optional<TranslationConfigEntity> config =
                translationConfigRepository.findByScopeTypeAndScopeId(scopeType, scopeId);

        if (config.isEmpty()) {
            // 翻訳設定が未作成の場合、enabled_languages は空なのでどの言語も不可
            throw new BusinessException(TranslationErrorCode.TRANSLATION_003);
        }

        List<String> enabledLanguages = config.get().getEnabledLanguages();
        if (enabledLanguages == null || !enabledLanguages.contains(language)) {
            throw new BusinessException(TranslationErrorCode.TRANSLATION_003);
        }
    }
}
