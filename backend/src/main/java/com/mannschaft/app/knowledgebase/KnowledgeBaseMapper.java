package com.mannschaft.app.knowledgebase;

import com.mannschaft.app.knowledgebase.dto.KbPageResponse;
import com.mannschaft.app.knowledgebase.dto.KbPageRevisionResponse;
import com.mannschaft.app.knowledgebase.dto.KbPageRevisionSummaryResponse;
import com.mannschaft.app.knowledgebase.dto.KbPageSummaryResponse;
import com.mannschaft.app.knowledgebase.dto.KbTemplateResponse;
import com.mannschaft.app.knowledgebase.dto.KbUploadUrlResponse;
import com.mannschaft.app.knowledgebase.entity.KbPageEntity;
import com.mannschaft.app.knowledgebase.entity.KbPageRevisionEntity;
import com.mannschaft.app.knowledgebase.entity.KbTemplateEntity;
import com.mannschaft.app.knowledgebase.service.KbImageService.ImageUploadUrlResult;
import org.springframework.stereotype.Component;

/**
 * ナレッジベース Mapper。
 * エンティティ → DTO への変換を担当する。
 */
@Component
public class KnowledgeBaseMapper {

    /**
     * KbPageEntity → KbPageSummaryResponse 変換。
     *
     * @param entity ページエンティティ
     * @return ページサマリーレスポンス
     */
    public KbPageSummaryResponse toSummaryResponse(KbPageEntity entity) {
        return new KbPageSummaryResponse(
                entity.getId(),
                entity.getParent() != null ? entity.getParent().getId() : null,
                entity.getPath(),
                entity.getDepth(),
                entity.getTitle(),
                entity.getSlug(),
                entity.getIcon(),
                entity.getAccessLevel(),
                entity.getStatus(),
                entity.getViewCount(),
                entity.getVersion(),
                entity.getUpdatedAt()
        );
    }

    /**
     * KbPageEntity → KbPageResponse 変換（詳細用）。
     *
     * @param entity ページエンティティ
     * @return ページ詳細レスポンス
     */
    public KbPageResponse toResponse(KbPageEntity entity) {
        return new KbPageResponse(
                entity.getId(),
                entity.getScopeType(),
                entity.getScopeId(),
                entity.getParent() != null ? entity.getParent().getId() : null,
                entity.getPath(),
                entity.getDepth(),
                entity.getTitle(),
                entity.getSlug(),
                entity.getBody(),
                entity.getIcon(),
                entity.getAccessLevel(),
                entity.getStatus(),
                entity.getViewCount(),
                entity.getCreatedBy(),
                entity.getLastEditedBy(),
                entity.getVersion(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    /**
     * KbPageRevisionEntity → KbPageRevisionSummaryResponse 変換（一覧用）。
     *
     * @param entity リビジョンエンティティ
     * @return リビジョンサマリーレスポンス
     */
    public KbPageRevisionSummaryResponse toRevisionSummary(KbPageRevisionEntity entity) {
        return new KbPageRevisionSummaryResponse(
                entity.getId(),
                entity.getRevisionNumber(),
                entity.getEditorId(),
                entity.getChangeSummary(),
                entity.getCreatedAt()
        );
    }

    /**
     * KbPageRevisionEntity → KbPageRevisionResponse 変換（詳細用）。
     *
     * @param entity リビジョンエンティティ
     * @return リビジョン詳細レスポンス
     */
    public KbPageRevisionResponse toRevisionResponse(KbPageRevisionEntity entity) {
        return new KbPageRevisionResponse(
                entity.getId(),
                entity.getKbPageId(),
                entity.getRevisionNumber(),
                entity.getTitle(),
                entity.getBody(),
                entity.getEditorId(),
                entity.getChangeSummary(),
                entity.getCreatedAt()
        );
    }

    /**
     * KbTemplateEntity → KbTemplateResponse 変換。
     * scopeType は String → KbTemplateScopeType に変換する。
     * 変換できない値（不正な文字列）は null を返す。
     *
     * @param entity テンプレートエンティティ
     * @return テンプレートレスポンス
     */
    public KbTemplateResponse toTemplateResponse(KbTemplateEntity entity) {
        KbTemplateScopeType scopeType = null;
        if (entity.getScopeType() != null) {
            try {
                scopeType = KbTemplateScopeType.valueOf(entity.getScopeType());
            } catch (IllegalArgumentException ignored) {
                // 未知のscopeTypeはnullとして扱う
            }
        }
        return new KbTemplateResponse(
                entity.getId(),
                scopeType,
                entity.getScopeId(),
                entity.getName(),
                entity.getBody(),
                entity.getIcon(),
                Boolean.TRUE.equals(entity.getIsSystem()),
                entity.getVersion()
        );
    }

    /**
     * ImageUploadUrlResult → KbUploadUrlResponse 変換。
     *
     * @param result サービス層の画像アップロード結果
     * @return アップロードURLレスポンス
     */
    public KbUploadUrlResponse toUploadUrlResponse(ImageUploadUrlResult result) {
        return new KbUploadUrlResponse(
                result.uploadUrl(),
                result.s3Key(),
                result.imageUrl()
        );
    }
}
