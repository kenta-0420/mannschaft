package com.mannschaft.app.recruitment.dto;

import com.mannschaft.app.recruitment.entity.RecruitmentTemplateEntity;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * F03.11 募集型予約 Phase 3: テンプレートレスポンス DTO。
 * ネスト record でフィールドをドメイン別にグループ化する。
 */
@Builder(toBuilder = true)
@Getter
public class RecruitmentTemplateResponse {

    Long id;
    TemplateScope scope;
    TemplateContent content;
    TemplateDefaults defaultSettings;
    TemplatePayment defaultPayment;
    TemplateResource defaultResource;
    TemplateAudit audit;

    public record TemplateScope(
            String scopeType,
            Long scopeId,
            Long categoryId,
            Long subcategoryId
    ) {}

    public record TemplateContent(
            String templateName,
            String title,
            String description
    ) {}

    public record TemplateDefaults(
            String participationType,
            Integer defaultCapacity,
            Integer defaultMinCapacity,
            Integer defaultDurationMinutes,
            Integer defaultApplicationDeadlineHours,
            Integer defaultAutoCancelHours
    ) {}

    public record TemplatePayment(
            Boolean defaultPaymentEnabled,
            Integer defaultPrice
    ) {}

    public record TemplateResource(
            String defaultVisibility,
            String defaultLocation,
            Long defaultReservationLineId,
            String defaultImageUrl,
            Long defaultCancellationPolicyId
    ) {}

    public record TemplateAudit(
            Long createdBy,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            LocalDateTime deletedAt
    ) {}

    /** Entity からレスポンス DTO を生成するファクトリメソッド。 */
    public static RecruitmentTemplateResponse from(RecruitmentTemplateEntity entity) {
        return RecruitmentTemplateResponse.builder()
                .id(entity.getId())
                .scope(new TemplateScope(
                        entity.getScopeType() != null ? entity.getScopeType().name() : null,
                        entity.getScopeId(),
                        entity.getCategoryId(),
                        entity.getSubcategoryId()))
                .content(new TemplateContent(
                        entity.getTemplateName(),
                        entity.getTitle(),
                        entity.getDescription()))
                .defaultSettings(new TemplateDefaults(
                        entity.getParticipationType() != null ? entity.getParticipationType().name() : null,
                        entity.getDefaultCapacity(),
                        entity.getDefaultMinCapacity(),
                        entity.getDefaultDurationMinutes(),
                        entity.getDefaultApplicationDeadlineHours(),
                        entity.getDefaultAutoCancelHours()))
                .defaultPayment(new TemplatePayment(
                        entity.getDefaultPaymentEnabled(),
                        entity.getDefaultPrice()))
                .defaultResource(new TemplateResource(
                        entity.getDefaultVisibility() != null ? entity.getDefaultVisibility().name() : null,
                        entity.getDefaultLocation(),
                        entity.getDefaultReservationLineId(),
                        entity.getDefaultImageUrl(),
                        entity.getDefaultCancellationPolicyId()))
                .audit(new TemplateAudit(
                        entity.getCreatedBy(),
                        entity.getCreatedAt(),
                        entity.getUpdatedAt(),
                        entity.getDeletedAt()))
                .build();
    }
}
