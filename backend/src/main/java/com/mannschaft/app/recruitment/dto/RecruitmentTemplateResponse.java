package com.mannschaft.app.recruitment.dto;

import com.mannschaft.app.recruitment.RecruitmentParticipationType;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.RecruitmentVisibility;
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
}
