package com.mannschaft.app.recruitment.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * F03.11 Phase 5b: ペナルティ設定レスポンス DTO。
 */
@Getter
@AllArgsConstructor
public class RecruitmentPenaltySettingResponse {

    private final Long id;
    private final String scopeType;
    private final Long scopeId;
    private final boolean isEnabled;
    private final int thresholdCount;
    private final int thresholdPeriodDays;
    private final int penaltyDurationDays;
    private final String applyScope;
    private final boolean autoNoShowDetection;
    private final int disputeAllowedDays;
    private final String createdAt;
    private final String updatedAt;
}
