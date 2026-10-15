package com.mannschaft.app.recruitment.dto;

import com.mannschaft.app.recruitment.CancellationFeeType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F03.11 キャンセルポリシー段階のリクエスト DTO。
 */
@Getter
@RequiredArgsConstructor
public class CancellationPolicyTierRequest {

    @NotNull
    @Min(1)
    @Max(4)
    private final Integer tierOrder;

    @NotNull
    @Positive
    private final Integer appliesAtOrBeforeHours;

    @NotNull
    private final CancellationFeeType feeType;

    @NotNull
    @Positive
    private final Integer feeValue;
}
