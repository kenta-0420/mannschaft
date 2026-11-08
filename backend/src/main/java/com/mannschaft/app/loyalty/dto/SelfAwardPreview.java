package com.mannschaft.app.loyalty.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.mannschaft.app.loyalty.SelfAwardMode;

/** チームポイントの業務契約。 */
@Schema(name = "LoyaltySelfAwardPreview")
public record SelfAwardPreview(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        Long teamId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String teamName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        RuleResponse rule,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        long programVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        SelfAwardMode selfAwardMode,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        int balance,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        boolean isEligibleForAward,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        boolean isAlreadyAwardedToday) {
}
