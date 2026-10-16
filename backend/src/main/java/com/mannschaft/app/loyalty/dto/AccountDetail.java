package com.mannschaft.app.loyalty.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/** チームポイントの業務契約。 */
@Schema(name = "LoyaltyAccountDetail")
public record AccountDetail(
        UUID accountId,
        Long teamId,
        String teamName,
        int balance,
        long lifetimeEarned,
        boolean isProgramEnabled,
        boolean isEligibleForAward,
        List<RewardProgress> rewards) {
}
