package com.mannschaft.app.loyalty.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.mannschaft.app.loyalty.SelfAwardMode;
import java.util.List;

/** チームポイントの業務契約。 */
@Schema(name = "LoyaltyOperationCatalog")
public record OperationCatalog(
        Long teamId,
        String teamName,
        boolean isEnabled,
        Long programVersion,
        SelfAwardMode selfAwardMode,
        RuleResponse rule,
        List<RewardResponse> rewards) {
}
