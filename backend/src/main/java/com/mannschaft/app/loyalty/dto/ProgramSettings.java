package com.mannschaft.app.loyalty.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.mannschaft.app.loyalty.Audience;
import com.mannschaft.app.loyalty.SelfAwardMode;
import java.util.List;
import java.util.UUID;

/** チームポイントの業務契約。 */
@Schema(name = "LoyaltyProgramSettings")
public record ProgramSettings(
        UUID id,
        Long teamId,
        boolean isEnabled,
        Audience targetAudience,
        SelfAwardMode selfAwardMode,
        Long version,
        List<RuleResponse> rules,
        List<RewardResponse> rewards) {
}
