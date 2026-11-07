package com.mannschaft.app.loyalty.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.mannschaft.app.loyalty.Audience;
import com.mannschaft.app.loyalty.SelfAwardMode;
import java.util.List;
import java.util.UUID;

/** チームポイントの業務契約。 */
@Schema(name = "LoyaltyProgramSettings")
public record ProgramSettings(
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        Long teamId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        boolean isEnabled,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        Audience targetAudience,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        SelfAwardMode selfAwardMode,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        Long version,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        List<RuleResponse> rules,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        List<RewardResponse> rewards) {
}
