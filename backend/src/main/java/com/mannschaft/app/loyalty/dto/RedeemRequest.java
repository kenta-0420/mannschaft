package com.mannschaft.app.loyalty.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** チームポイントの業務契約。 */
@Schema(name = "LoyaltyRedeemRequest")
public record RedeemRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 512) String qrToken,
        @NotNull UUID rewardId,
        @NotNull @PositiveOrZero Long expectedRewardVersion,
        @NotNull @PositiveOrZero Long expectedProgramVersion) {
}
