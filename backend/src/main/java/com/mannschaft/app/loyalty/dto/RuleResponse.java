package com.mannschaft.app.loyalty.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.mannschaft.app.loyalty.Frequency;
import java.util.UUID;

/** チームポイントの業務契約。 */
@Schema(name = "LoyaltyRuleResponse")
public record RuleResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        int points,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        Frequency frequency,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        boolean isSelfAwardEnabled,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        boolean isActive,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        long version) {
}
