package com.mannschaft.app.loyalty.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/** チームポイントの業務契約。 */
@Schema(name = "LoyaltyRewardResponse")
public record RewardResponse(
        UUID id,
        String name,
        String description,
        int requiredPoints,
        boolean isActive,
        long version) {
}
