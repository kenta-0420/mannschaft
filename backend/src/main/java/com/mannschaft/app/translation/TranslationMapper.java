package com.mannschaft.app.translation;

import com.mannschaft.app.translation.entity.ContentTranslationEntity;
import com.mannschaft.app.translation.entity.TranslationAssignmentEntity;
import com.mannschaft.app.translation.entity.TranslationConfigEntity;
import com.mannschaft.app.translation.service.ContentTranslationService.ContentTranslationResponse;
import com.mannschaft.app.translation.service.ContentTranslationService.TranslationSummaryResponse;
import com.mannschaft.app.translation.service.TranslationAssignmentService.TranslationAssignmentResponse;
import com.mannschaft.app.translation.service.TranslationConfigService.TranslationConfigResponse;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 多言語コンテンツドメインのエンティティ → レスポンスDTO変換マッパー。
 * MapStructは未導入のため、手動マッピングで実装する。
 */
@Component
public class TranslationMapper {

    // ========================================
    // ContentTranslationEntity 変換
    // ========================================

    /**
     * ContentTranslationEntity を ContentTranslationResponse（詳細レスポンス）に変換する。
     *
     * @param entity 翻訳コンテンツエンティティ
     * @return 翻訳コンテンツ詳細レスポンス
     */
    public ContentTranslationResponse toContentTranslationResponse(ContentTranslationEntity entity) {
        return new ContentTranslationResponse(entity);
    }

    /**
     * ContentTranslationEntity を TranslationSummaryResponse（一覧用サマリー）に変換する。
     *
     * @param entity 翻訳コンテンツエンティティ
     * @return 翻訳サマリーレスポンス
     */
    public TranslationSummaryResponse toTranslationSummaryResponse(ContentTranslationEntity entity) {
        return new TranslationSummaryResponse(entity);
    }

    /**
     * ContentTranslationEntity リストを TranslationSummaryResponse リストに変換する。
     *
     * @param entities 翻訳コンテンツエンティティリスト
     * @return 翻訳サマリーレスポンスリスト
     */
    public List<TranslationSummaryResponse> toTranslationSummaryResponseList(
            List<ContentTranslationEntity> entities) {
        return entities.stream()
                .map(this::toTranslationSummaryResponse)
                .toList();
    }

    // ========================================
    // TranslationConfigEntity 変換
    // ========================================

    /**
     * TranslationConfigEntity を TranslationConfigResponse に変換する。
     *
     * @param entity 翻訳設定エンティティ
     * @return 翻訳設定レスポンス
     */
    public TranslationConfigResponse toTranslationConfigResponse(TranslationConfigEntity entity) {
        return new TranslationConfigResponse(
                entity.getId(),
                entity.getScopeType(),
                entity.getScopeId(),
                entity.getPrimaryLanguage(),
                entity.getEnabledLanguages(),
                Boolean.TRUE.equals(entity.getIsAutoDetectReaderLanguage())
        );
    }

    // ========================================
    // TranslationAssignmentEntity 変換
    // ========================================

    /**
     * TranslationAssignmentEntity を TranslationAssignmentResponse に変換する。
     *
     * @param entity アサインエンティティ
     * @return アサインレスポンス
     */
    public TranslationAssignmentResponse toTranslationAssignmentResponse(
            TranslationAssignmentEntity entity) {
        return new TranslationAssignmentResponse(entity);
    }

    /**
     * TranslationAssignmentEntity リストを TranslationAssignmentResponse リストに変換する。
     *
     * @param entities アサインエンティティリスト
     * @return アサインレスポンスリスト
     */
    public List<TranslationAssignmentResponse> toTranslationAssignmentResponseList(
            List<TranslationAssignmentEntity> entities) {
        return entities.stream()
                .map(TranslationAssignmentResponse::new)
                .toList();
    }
}
