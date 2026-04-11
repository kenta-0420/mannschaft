package com.mannschaft.app.recruitment.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * F03.11 キャンセルポリシー段階のレスポンス。
 */
@Getter
@AllArgsConstructor
public class CancellationPolicyTierResponse {

    private final Long id;
    private final Long policyId;
    private final Integer tierOrder;
    private final Integer appliesAtOrBeforeHours;
    private final String feeType;
    private final Integer feeValue;
}
