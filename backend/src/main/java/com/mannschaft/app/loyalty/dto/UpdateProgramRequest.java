package com.mannschaft.app.loyalty.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.mannschaft.app.loyalty.Audience;
import com.mannschaft.app.loyalty.SelfAwardMode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** チームポイントの業務契約。 */
@Schema(name = "LoyaltyUpdateProgramRequest")
public record UpdateProgramRequest(
        @Schema(types = {"integer", "null"}, nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        @PositiveOrZero Long expectedVersion,
        @NotNull Boolean isEnabled,
        @NotNull Audience targetAudience,
        @NotNull SelfAwardMode selfAwardMode) {
}
