package com.mannschaft.app.facility.dto;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 施設予約設定更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateSettingsRequest {

    private final Boolean requiresApproval;

    @Min(1)
    private final Integer maxBookingsPerDayPerUser;

    private final Boolean allowStripePayment;

    @Min(0)
    private final Integer cancellationDeadlineHours;

    private final Boolean noShowPenaltyEnabled;

    @Min(1)
    private final Integer noShowPenaltyThreshold;

    @Min(1)
    private final Integer noShowPenaltyDays;
}
