package com.mannschaft.app.loyalty.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** チームポイントの業務契約。 */
@Schema(name = "LoyaltyCreateRewardRequest")
public record CreateRewardRequest(
        @NotBlank @Size(max = 100) String name,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        @Size(max = 500) String description,
        @NotNull @Min(1) @Max(1000000) Integer requiredPoints) {
    /** Bean Validationへ渡す前に設計どおり名称の前後空白を除く。 */
    public CreateRewardRequest {
        name = name == null ? null : name.strip();
    }
}
