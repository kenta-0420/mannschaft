package com.mannschaft.app.translation.service;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.translation.TranslationErrorCode;
import com.mannschaft.app.translation.entity.ContentTranslationEntity;
import com.mannschaft.app.translation.entity.TranslationAssignmentEntity;
import com.mannschaft.app.translation.repository.ContentTranslationRepository;
import com.mannschaft.app.translation.repository.TranslationAssignmentRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 翻訳者アサイン管理サービス。
 * 翻訳者（ユーザー）をスコープ・言語単位でアサインし、その一覧管理・削除を担う。
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class TranslationAssignmentService {

    private final TranslationAssignmentRepository translationAssignmentRepository;
    private final ContentTranslationRepository contentTranslationRepository;

    // ========================================
    // リクエスト DTO
    // ========================================

    /**
     * 翻訳者アサイン作成リクエスト。
     * translationId で指定した翻訳コンテンツの language および scopeType/scopeId を使ってアサインを作成する。
     */
    @Getter
    @Setter
    public static class AssignTranslatorRequest {
        /** アサイン対象の翻訳コンテンツID（存在確認 + 言語・スコープ情報の取得に使用） */
        private Long translationId;
        /** アサインするユーザーID */
        private Long assigneeId;
        /** 備考（任意。現バージョンでは参照のみ保持。将来拡張用） */
        private String note;
    }

    // ========================================
    // レスポンス DTO
    // ========================================

    /**
     * 翻訳者アサインレスポンス。
     */
    @Getter
    public static class TranslationAssignmentResponse {
        private final Long id;
        private final String scopeType;
        private final Long scopeId;
        private final Long assigneeId;
        private final String language;
        private final boolean active;
        private final java.time.LocalDateTime createdAt;

        public TranslationAssignmentResponse(TranslationAssignmentEntity entity) {
            this.id = entity.getId();
            this.scopeType = entity.getScopeType();
            this.scopeId = entity.getScopeId();
            this.assigneeId = entity.getUserId();
            this.language = entity.getLanguage();
            this.active = entity.getIsActive();
            this.createdAt = entity.getCreatedAt();
        }
    }

    // ========================================
    // 公開メソッド
    // ========================================

    /**
     * 翻訳者をアサインする。
     * 指定した翻訳コンテンツの存在確認後、スコープ・ユーザー・言語でアサインを作成する。
     * 同一スコープ×ユーザー×言語のアサインが既に存在する場合は既存レコードを返す（冪等）。
     *
     * @param assignedBy アサイン操作者のユーザーID（ログ記録用）
     * @param req        アサインリクエスト
     * @return 作成または既存のアサインレスポンス
     * @throws BusinessException TRANSLATION_002: 翻訳コンテンツが見つからない場合
     */
    @Transactional
    public ApiResponse<TranslationAssignmentResponse> assignTranslator(
            Long assignedBy, AssignTranslatorRequest req) {

        // 翻訳コンテンツの存在確認（スコープ情報と言語を取得）
        ContentTranslationEntity translation = contentTranslationRepository.findById(req.getTranslationId())
                .orElseThrow(() -> new BusinessException(TranslationErrorCode.TRANSLATION_002));

        String scopeType = translation.getScopeType();
        Long scopeId = translation.getScopeId();
        String language = translation.getLanguage();

        // 同一スコープ×ユーザー×言語の重複チェック
        boolean exists = translationAssignmentRepository
                .existsByScopeTypeAndScopeIdAndUserIdAndLanguageAndIsActiveTrue(
                        scopeType, scopeId, req.getAssigneeId(), language);

        if (exists) {
            // 既存アサインを返す（冪等性保証）
            List<TranslationAssignmentEntity> existingList =
                    translationAssignmentRepository.findByScopeTypeAndScopeIdAndUserIdAndIsActiveTrue(
                            scopeType, scopeId, req.getAssigneeId());

            TranslationAssignmentEntity existing = existingList.stream()
                    .filter(a -> language.equals(a.getLanguage()))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(TranslationErrorCode.TRANSLATION_009));

            log.debug("翻訳者アサイン既存返却: id={}, scope={}/{}, userId={}, language={}",
                    existing.getId(), scopeType, scopeId, req.getAssigneeId(), language);
            return ApiResponse.of(new TranslationAssignmentResponse(existing));
        }

        // 新規アサイン作成
        TranslationAssignmentEntity entity = TranslationAssignmentEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .userId(req.getAssigneeId())
                .language(language)
                .isActive(true)
                .build();

        TranslationAssignmentEntity saved = translationAssignmentRepository.save(entity);
        log.info("翻訳者アサイン作成: id={}, scope={}/{}, userId={}, language={}, assignedBy={}",
                saved.getId(), scopeType, scopeId, req.getAssigneeId(), language, assignedBy);

        return ApiResponse.of(new TranslationAssignmentResponse(saved));
    }

    /**
     * スコープに紐づくアサイン一覧を取得する。
     * translationIdから翻訳コンテンツのスコープ情報を参照してアサイン一覧を返す。
     *
     * @param translationId 翻訳コンテンツID（スコープ情報の取得に使用）
     * @return アサインレスポンスのリスト
     * @throws BusinessException TRANSLATION_002: 翻訳コンテンツが見つからない場合
     */
    public ApiResponse<List<TranslationAssignmentResponse>> listAssignments(Long translationId) {
        ContentTranslationEntity translation = contentTranslationRepository.findById(translationId)
                .orElseThrow(() -> new BusinessException(TranslationErrorCode.TRANSLATION_002));

        List<TranslationAssignmentEntity> assignments =
                translationAssignmentRepository.findByScopeTypeAndScopeIdAndIsActiveTrue(
                        translation.getScopeType(), translation.getScopeId());

        List<TranslationAssignmentResponse> responses = assignments.stream()
                .map(TranslationAssignmentResponse::new)
                .collect(Collectors.toList());

        return ApiResponse.of(responses);
    }

    /**
     * 指定スコープで自分にアサインされた翻訳タスク一覧を取得する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param userId    ログインユーザーID
     * @return アサインレスポンスのリスト
     */
    public ApiResponse<List<TranslationAssignmentResponse>> listMyAssignments(
            String scopeType, Long scopeId, Long userId) {

        List<TranslationAssignmentEntity> assignments =
                translationAssignmentRepository.findByScopeTypeAndScopeIdAndUserIdAndIsActiveTrue(
                        scopeType, scopeId, userId);

        List<TranslationAssignmentResponse> responses = assignments.stream()
                .map(TranslationAssignmentResponse::new)
                .collect(Collectors.toList());

        return ApiResponse.of(responses);
    }

    /**
     * アサインを物理削除する。
     *
     * @param id アサインID
     * @throws BusinessException TRANSLATION_009: アサインが見つからない場合
     */
    @Transactional
    public void removeAssignment(Long id) {
        if (!translationAssignmentRepository.existsById(id)) {
            throw new BusinessException(TranslationErrorCode.TRANSLATION_009);
        }
        translationAssignmentRepository.deleteById(id);
        log.info("翻訳者アサイン物理削除: id={}", id);
    }

}
