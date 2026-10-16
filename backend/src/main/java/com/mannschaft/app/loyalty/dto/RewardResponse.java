package com.mannschaft.app.loyalty.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/** チームポイントの業務契約。 */
@Schema(name = "LoyaltyRewardResponse")
public record RewardResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String name,
        @Schema(types = {"string", "null"}, nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        String description,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        int requiredPoints,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        boolean isActive,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        long version) {
}
