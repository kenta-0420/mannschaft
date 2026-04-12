package com.mannschaft.app.recruitment.dto;

import com.mannschaft.app.recruitment.PenaltyApplyScope;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * F03.11 Phase 5b: ペナルティ設定 UPSERT リクエスト DTO。
 */
@Getter
@NoArgsConstructor
public class UpsertPenaltySettingRequest {

    private boolean isEnabled = true;

    @Min(1)
    @Max(10)
    private int thresholdCount = 3;

    @Min(1)
    @Max(365)
    private int thresholdPeriodDays = 180;

    @Min(1)
    @Max(365)
    private int penaltyDurationDays = 30;

    private PenaltyApplyScope applyScope = PenaltyApplyScope.THIS_SCOPE_ONLY;

    private boolean autoNoShowDetection = false;

    @Min(0)
    @Max(30)
    private int disputeAllowedDays = 14;
}
