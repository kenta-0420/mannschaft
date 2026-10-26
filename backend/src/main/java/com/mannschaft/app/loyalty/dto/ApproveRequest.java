package com.mannschaft.app.loyalty.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

/** チームポイントの業務契約。 */
@Schema(name = "LoyaltyApproveRequest")
public record ApproveRequest(
        @NotNull @PositiveOrZero Long expectedRequestVersion,
        @NotNull @Pattern(regexp = "[A-Z2-7]{8}") String verificationCode) {
}
