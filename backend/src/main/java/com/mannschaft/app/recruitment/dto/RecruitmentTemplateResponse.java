package com.mannschaft.app.recruitment.dto;

import com.mannschaft.app.recruitment.RecruitmentParticipationType;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.RecruitmentVisibility;
import com.mannschaft.app.recruitment.entity.RecruitmentTemplateEntity;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * F03.11 募集型予約 Phase 3: テンプレートレスポンス DTO。
 */
@Getter
@RequiredArgsConstructor
public class RecruitmentTemplateResponse {

    private final Long id;
    private final RecruitmentScopeType scopeType;
    private final Long scopeId;
    private final Long categoryId;
    private final Long subcategoryId;
    private final String templateName;
    private final String title;
    private final String description;
    private final RecruitmentParticipationType participationType;
    private final Integer defaultCapacity;
    private final Integer defaultMinCapacity;
    private final Integer defaultDurationMinutes;
    private final Integer defaultApplicationDeadlineHours;
    private final Integer defaultAutoCancelHours;
    private final Boolean defaultPaymentEnabled;
    private final Integer defaultPrice;
    private final RecruitmentVisibility defaultVisibility;
    private final String defaultLocation;
    private final Long defaultReservationLineId;
    private final String defaultImageUrl;
    private final Long defaultCancellationPolicyId;
    private final Long createdBy;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
    private final LocalDateTime deletedAt;

    /** Entity からレスポンス DTO を生成するファクトリメソッド。 */
    public static RecruitmentTemplateResponse from(RecruitmentTemplateEntity entity) {
        return new RecruitmentTemplateResponse(
                entity.getId(),
                entity.getScopeType(),
                entity.getScopeId(),
                entity.getCategoryId(),
                entity.getSubcategoryId(),
                entity.getTemplateName(),
                entity.getTitle(),
                entity.getDescription(),
                entity.getParticipationType(),
                entity.getDefaultCapacity(),
                entity.getDefaultMinCapacity(),
                entity.getDefaultDurationMinutes(),
                entity.getDefaultApplicationDeadlineHours(),
                entity.getDefaultAutoCancelHours(),
                entity.getDefaultPaymentEnabled(),
                entity.getDefaultPrice(),
                entity.getDefaultVisibility(),
                entity.getDefaultLocation(),
                entity.getDefaultReservationLineId(),
                entity.getDefaultImageUrl(),
                entity.getDefaultCancellationPolicyId(),
                entity.getCreatedBy(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getDeletedAt()
        );
    }
}
