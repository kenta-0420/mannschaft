package com.mannschaft.app.loyalty.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.mannschaft.app.loyalty.SelfAwardMode;
import java.util.List;

/** チームポイントの業務契約。 */
@Schema(name = "LoyaltyOperationCatalog")
public record OperationCatalog(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        Long teamId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String teamName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        boolean isEnabled,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        Long programVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        SelfAwardMode selfAwardMode,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        RuleResponse rule,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        List<RewardResponse> rewards) {
}
