package com.mannschaft.app.disclosure.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.disclosure.entity.DisclosureFormTemplateEntity;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 重要事項説明書 様式テンプレート レスポンス DTO（F09.14 Phase 2-β-4）。
 *
 * <p>設計書 §4 GET /disclosure-templates 系エンドポイントのレスポンス形状。
 * 一覧では {@code formSchema} を含めずに軽量化することも可能だが、本フェーズでは
 * 詳細含めて統一構造で返す。フロント側で必要なフィールドのみ参照する。</p>
 */
public record DisclosureFormTemplateResponse(
        Long id,
        String code,
        String name,
        String prefectureCode,
        String version,
        Boolean isStandard,
        Boolean isSystemTemplate,
        String scopeType,
        Long scopeId,
        JsonNode formSchema,
        String pdfTemplatePath,
        String excelTemplateKey,
        LocalDate effectiveFrom,
        LocalDate effectiveUntil,
        Boolean isActive,
        Long createdBy,
        Long versionLock,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    /**
     * Entity からレスポンスへ変換する。{@code formSchema} は呼び出し側で
     * 既に {@link JsonNode} 化したものを渡す（一覧では null も許容）。
     */
    public static DisclosureFormTemplateResponse from(DisclosureFormTemplateEntity entity, JsonNode formSchema) {
        return new DisclosureFormTemplateResponse(
                entity.getId(),
                entity.getCode(),
                entity.getName(),
                entity.getPrefectureCode(),
                entity.getVersion(),
                entity.getIsStandard(),
                entity.getIsSystemTemplate(),
                entity.getScopeType(),
                entity.getScopeId(),
                formSchema,
                entity.getPdfTemplatePath(),
                entity.getExcelTemplateKey(),
                entity.getEffectiveFrom(),
                entity.getEffectiveUntil(),
                entity.getIsActive(),
                entity.getCreatedBy(),
                entity.getVersionLock(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
