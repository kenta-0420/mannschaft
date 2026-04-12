package com.mannschaft.app.recruitment.dto;

import com.mannschaft.app.recruitment.RecruitmentParticipationType;
import com.mannschaft.app.recruitment.RecruitmentVisibility;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F03.11 募集型予約 Phase 3: テンプレート作成リクエスト。
 */
@Getter
@RequiredArgsConstructor
public class RecruitmentTemplateCreateRequest {

    @NotNull
    @Size(max = 100)
    private final String templateName;

    @NotNull
    private final Long categoryId;

    private final Long subcategoryId;

    @NotNull
    @Size(max = 100)
    private final String title;

    private final String description;

    @NotNull
    private final RecruitmentParticipationType participationType;

    @NotNull
    @Positive
    private final Integer defaultCapacity;

    @NotNull
    @Positive
    private final Integer defaultMinCapacity;

    @NotNull
    @Positive
    private final Integer defaultDurationMinutes;

    @NotNull
    @Positive
    private final Integer defaultApplicationDeadlineHours;

    @NotNull
    @Positive
    private final Integer defaultAutoCancelHours;

    @NotNull
    private final Boolean defaultPaymentEnabled;

    private final Integer defaultPrice;

    @NotNull
    private final RecruitmentVisibility defaultVisibility;

    @Size(max = 200)
    private final String defaultLocation;

    private final Long defaultReservationLineId;

    @Size(max = 500)
    private final String defaultImageUrl;

    private final Long defaultCancellationPolicyId;
}
