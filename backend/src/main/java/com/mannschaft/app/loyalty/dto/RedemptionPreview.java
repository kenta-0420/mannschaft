package com.mannschaft.app.loyalty.dto;

import io.swagger.v3.oas.annotations.media.Schema;


/** チームポイントの業務契約。 */
@Schema(name = "LoyaltyRedemptionPreview")
public record RedemptionPreview(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String displayName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        int balance,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        RewardResponse reward,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        long programVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        boolean canRedeem) {
}
