package com.mannschaft.app.recruitment.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * F03.11 キャンセルポリシーの作成リクエスト。
 */
@Getter
@RequiredArgsConstructor
public class CreateCancellationPolicyRequest {

    @Size(max = 100)
    private final String policyName;

    @NotNull
    @Positive
    private final Integer freeUntilHoursBefore;

    @NotNull
    private final Boolean isTemplatePolicy;

    @Valid
    private final List<CancellationPolicyTierRequest> tiers;
}
