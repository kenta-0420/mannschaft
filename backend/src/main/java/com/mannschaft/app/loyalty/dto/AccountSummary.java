package com.mannschaft.app.loyalty.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

/** チームポイントの業務契約。 */
@Schema(name = "LoyaltyAccountSummary")
public record AccountSummary(
        UUID accountId,
        Long teamId,
        String teamName,
        int balance,
        long lifetimeEarned,
        LocalDateTime updatedAt) {
}
