package com.mannschaft.app.recruitment.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * F03.11 キャンセル料試算結果 (§9.9 / §5.9)。
 */
@Getter
@AllArgsConstructor
public class CancellationFeeEstimateResponse {

    private final Long listingId;
    private final Long policyId;
    private final Integer feeAmount;
    private final Long appliedTierId;
    private final Integer tierOrder;
    private final String feeType;
    /** true=無料境界より前のため料金無し、false=料金あり */
    private final Boolean freeUntilApplied;
    private final Double hoursBeforeStart;
    private final LocalDateTime calculatedAt;
}
