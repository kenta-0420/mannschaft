package com.mannschaft.app.translation.service;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.translation.TranslationErrorCode;
import com.mannschaft.app.translation.TranslationStatus;
import com.mannschaft.app.translation.entity.ContentTranslationEntity;
import com.mannschaft.app.translation.repository.ContentTranslationRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 翻訳コンテンツ管理サービス。
 * 翻訳の作成・取得・更新・公開・論理削除を担う。
 * また原文更新時の一括ステータス更新（markAsStale）を提供する。
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ContentTranslationService {

    private final ContentTranslationRepository contentTranslationRepository;

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
        private String contentType;
        /** 原文コンテンツID */
        private Long contentId;
        /** 翻訳先言語コード（ISO 639-1） */
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
        private final String contentType;
        private final Long contentId;
        private final String language;
        private final String title;
        private final String body;
        private final String summary;
        private final String status;
        private final Long translatedBy;
        private final LocalDateTime publishedAt;
        private final LocalDateTime createdAt;
        private final LocalDateTime updatedAt;
        private final Long version;

        public ContentTranslationResponse(Long id, String contentType, Long contentId,
                                           String language, String title, String body, String summary,
                                           String status, Long translatedBy, LocalDateTime publishedAt,
                                           LocalDateTime createdAt, LocalDateTime updatedAt, Long version) {
            this.id = id;
            this.contentType = contentType;
            this.contentId = contentId;
            this.language = language;
            this.title = title;
            this.body = body;
            this.summary = summary;
            this.status = status;
            this.translatedBy = translatedBy;
            this.publishedAt = publishedAt;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.version = version;
        }
    }

    /**
     * 翻訳コンテンツ一覧用サマリーレスポンス。
     */
    @Getter
    public static class TranslationSummaryResponse {
        private final Long id;
        private final String language;
        private final String status;
        private final Long translatedBy;
        private final LocalDateTime updatedAt;

        public TranslationSummaryResponse(Long id, String language, String status,
                                           Long translatedBy, LocalDateTime updatedAt) {
            this.id = id;
            this.language = language;
            this.status = status;
            this.translatedBy = translatedBy;
            this.updatedAt = updatedAt;
        }
    }

    // ========================================
    // 公開メソッド
    // ========================================

    /**
     * 翻訳コンテンツを作成する。
     * 同一contentType+contentId+languageの翻訳が既に存在する場合はエラー。
     * ステータスは DRAFT で作成する。
     *
     * @param createdBy 作成者ユーザーID（翻訳者として記録）
     * @param req       作成リクエスト
     * @return 作成した翻訳コンテンツのレスポンス
     */
    @Transactional
    public ApiResponse<ContentTranslationResponse> createTranslation(
            Long createdBy, CreateTranslationRequest req) {

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

        return ApiResponse.of(toDetailResponse(saved));
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
        return ApiResponse.of(toDetailResponse(entity));
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

        return ApiResponse.of(toDetailResponse(entity));
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
                .map(this::toSummaryResponse)
                .collect(Collectors.toList());

        return ApiResponse.of(responses);
    }

    /**
     * 翻訳コンテンツを更新する。
     *
     * @param id        翻訳コンテンツID
     * @param updatedBy 更新者ユーザーID（ログ記録用）
     * @param req       更新リクエスト
     * @return 更新後の翻訳コンテンツのレスポンス
     * @throws BusinessException TRANSLATION_002: 対象が見つからない場合
     */
    @Transactional
    public ApiResponse<ContentTranslationResponse> updateTranslation(
            Long id, Long updatedBy, UpdateTranslationRequest req) {

        ContentTranslationEntity entity = findOrThrow(id);

        // 翻訳内容を更新（nullの場合は既存値を維持）
        String newTitle = req.getTitle() != null ? req.getTitle() : entity.getTranslatedTitle();
        String newBody = req.getBody() != null ? req.getBody() : entity.getTranslatedBody();
        String newSummary = req.getSummary() != null ? req.getSummary() : entity.getTranslatedExcerpt();

        entity.updateContent(newTitle, newBody, newSummary);
        ContentTranslationEntity saved = contentTranslationRepository.save(entity);
        log.info("翻訳コンテンツ更新: id={}, updatedBy={}", id, updatedBy);

        return ApiResponse.of(toDetailResponse(saved));
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
        return ApiResponse.of(toDetailResponse(saved));
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
     * エンティティを詳細レスポンスDTOに変換する。
     *
     * @param entity 翻訳コンテンツエンティティ
     * @return 詳細レスポンスDTO
     */
    private ContentTranslationResponse toDetailResponse(ContentTranslationEntity entity) {
        return new ContentTranslationResponse(
                entity.getId(),
                entity.getSourceType(),
                entity.getSourceId(),
                entity.getLanguage(),
                entity.getTranslatedTitle(),
                entity.getTranslatedBody(),
                entity.getTranslatedExcerpt(),
                entity.getStatus(),
                entity.getTranslatorId(),
                entity.getPublishedAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getVersion()
        );
    }

    /**
     * エンティティをサマリーレスポンスDTOに変換する。
     *
     * @param entity 翻訳コンテンツエンティティ
     * @return サマリーレスポンスDTO
     */
    private TranslationSummaryResponse toSummaryResponse(ContentTranslationEntity entity) {
        return new TranslationSummaryResponse(
                entity.getId(),
                entity.getLanguage(),
                entity.getStatus(),
                entity.getTranslatorId(),
                entity.getUpdatedAt()
        );
    }
}
