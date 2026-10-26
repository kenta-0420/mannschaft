package com.mannschaft.app.recruitment.dto;

import com.mannschaft.app.recruitment.RecruitmentParticipationType;
import com.mannschaft.app.recruitment.RecruitmentVisibility;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F03.11 募集型予約 Phase 3: テンプレート更新リクエスト。
 * 全フィールドが null 許容（部分更新対応）。
 */
@Getter
@RequiredArgsConstructor
public class RecruitmentTemplateUpdateRequest {

    @Size(max = 100)
    private final String templateName;

    private final Long categoryId;

    private final Long subcategoryId;

    @Size(max = 100)
    private final String title;

    private final String description;

    private final RecruitmentParticipationType participationType;

    @Positive
    private final Integer defaultCapacity;

    @Positive
    private final Integer defaultMinCapacity;

    @Positive
    private final Integer defaultDurationMinutes;

    @Positive
    private final Integer defaultApplicationDeadlineHours;

    @Positive
    private final Integer defaultAutoCancelHours;

    private final Boolean defaultPaymentEnabled;

    private final Integer defaultPrice;

    private final RecruitmentVisibility defaultVisibility;

    @Size(max = 200)
    private final String defaultLocation;

    private final Long defaultReservationLineId;

    @Size(max = 500)
    private final String defaultImageUrl;

    private final Long defaultCancellationPolicyId;
}
