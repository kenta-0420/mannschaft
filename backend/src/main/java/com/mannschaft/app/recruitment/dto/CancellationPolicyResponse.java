package com.mannschaft.app.recruitment.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * F03.11 キャンセルポリシーのレスポンス (段階含む)。
 */
@Getter
@AllArgsConstructor
public class CancellationPolicyResponse {

    private final Long id;
    private final String scopeType;
    private final Long scopeId;
    private final String policyName;
    private final Integer freeUntilHoursBefore;
    private final Boolean isTemplatePolicy;
    private final Long createdBy;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
    private final List<CancellationPolicyTierResponse> tiers;
}
