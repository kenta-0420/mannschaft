package com.mannschaft.app.disclosure.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.disclosure.DraftStatus;
import com.mannschaft.app.disclosure.entity.DisclosureFormDraftEntity;

import java.time.LocalDateTime;

/**
 * 重要事項説明書 ドラフト レスポンス DTO（F09.14 Phase 2-β-4）。
 *
 * <p>設計書 §4 GET /disclosure-drafts 系エンドポイントのレスポンス形状。
 * {@code formData} と {@code referencedPackageIds} は JSON ノードで返却し、
 * フロント側のフォームレンダラがそのまま消費できる構造にする。</p>
 */
public record DisclosureFormDraftResponse(
        Long id,
        String scopeType,
        Long scopeId,
        Long templateId,
        String templateVersionSnapshot,
        String title,
        Long targetDwellingUnitId,
        JsonNode formData,
        JsonNode referencedPackageIds,
        DraftStatus status,
        Long createdBy,
        Long updatedBy,
        Long version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    /**
     * Entity からレスポンスへ変換する。JSON 文字列カラムは事前に {@link JsonNode} 化しておく。
     */
    public static DisclosureFormDraftResponse from(
            DisclosureFormDraftEntity entity,
            JsonNode formData,
            JsonNode referencedPackageIds) {
        return new DisclosureFormDraftResponse(
                entity.getId(),
                entity.getScopeType(),
                entity.getScopeId(),
                entity.getTemplateId(),
                entity.getTemplateVersionSnapshot(),
                entity.getTitle(),
                entity.getTargetDwellingUnitId(),
                formData,
                referencedPackageIds,
                entity.getStatus(),
                entity.getCreatedBy(),
                entity.getUpdatedBy(),
                entity.getVersion(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
