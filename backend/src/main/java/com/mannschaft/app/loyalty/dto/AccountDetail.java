package com.mannschaft.app.loyalty.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/** チームポイントの業務契約。 */
@Schema(name = "LoyaltyAccountDetail")
public record AccountDetail(
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        UUID accountId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        Long teamId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String teamName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        int balance,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        long lifetimeEarned,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        boolean isProgramEnabled,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        boolean isEligibleForAward,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        List<RewardProgress> rewards) {
}
