package com.mannschaft.app.loyalty.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.mannschaft.app.loyalty.Frequency;
import java.util.UUID;

/** チームポイントの業務契約。 */
@Schema(name = "LoyaltyRuleResponse")
public record RuleResponse(
        UUID id,
        String name,
        int points,
        Frequency frequency,
        boolean isSelfAwardEnabled,
        boolean isActive,
        long version) {
}
