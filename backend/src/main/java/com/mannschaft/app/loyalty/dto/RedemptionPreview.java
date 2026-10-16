package com.mannschaft.app.loyalty.dto;

import io.swagger.v3.oas.annotations.media.Schema;


/** チームポイントの業務契約。 */
@Schema(name = "LoyaltyRedemptionPreview")
public record RedemptionPreview(
        String displayName,
        int balance,
        RewardResponse reward,
        long programVersion,
        boolean canRedeem) {
}
