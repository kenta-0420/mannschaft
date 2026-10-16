package com.mannschaft.app.loyalty.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.mannschaft.app.loyalty.SelfAwardMode;

/** チームポイントの業務契約。 */
@Schema(name = "LoyaltySelfAwardPreview")
public record SelfAwardPreview(
        Long teamId,
        String teamName,
        RuleResponse rule,
        long programVersion,
        SelfAwardMode selfAwardMode,
        int balance,
        boolean isEligibleForAward,
        boolean isAlreadyAwardedToday) {
}
