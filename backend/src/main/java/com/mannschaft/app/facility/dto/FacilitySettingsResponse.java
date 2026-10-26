package com.mannschaft.app.facility.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 施設予約設定レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class FacilitySettingsResponse {

    private final Long id;
    private final String scopeType;
    private final Long scopeId;
    private final Boolean requiresApproval;
    private final Integer maxBookingsPerDayPerUser;
    private final Boolean allowStripePayment;
    private final Integer cancellationDeadlineHours;
    private final Boolean noShowPenaltyEnabled;
    private final Integer noShowPenaltyThreshold;
    private final Integer noShowPenaltyDays;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
