package com.mannschaft.app.loyalty.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** チームポイントの業務契約。 */
@Schema(name = "LoyaltyUpdateRewardRequest")
public record UpdateRewardRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 500) String description,
        @NotNull @Min(1) @Max(1000000) Integer requiredPoints,
        @NotNull @PositiveOrZero Long expectedVersion,
        @NotNull Boolean isActive) {
    /** Bean Validationへ渡す前に設計どおり名称の前後空白を除く。 */
    public UpdateRewardRequest {
        name = name == null ? null : name.strip();
    }
}
