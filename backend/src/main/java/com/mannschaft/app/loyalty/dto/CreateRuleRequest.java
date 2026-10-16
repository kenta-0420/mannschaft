package com.mannschaft.app.loyalty.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.mannschaft.app.loyalty.Frequency;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** チームポイントの業務契約。 */
@Schema(name = "LoyaltyCreateRuleRequest")
public record CreateRuleRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull @Min(1) @Max(10000) Integer points,
        @NotNull Frequency frequency,
        @NotNull Boolean isSelfAwardEnabled) {
}
