package com.mannschaft.app.loyalty.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.mannschaft.app.loyalty.TransactionChannel;
import com.mannschaft.app.loyalty.TransactionKind;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/** チームポイントの業務契約。 */
@Schema(name = "LoyaltyTransactionResponse")
public record TransactionResponse(
        UUID id,
        Long teamId,
        TransactionKind kind,
        TransactionChannel channel,
        int delta,
        int balanceAfter,
        UUID ruleId,
        UUID rewardId,
        String label,
        LocalDate awardOn,
        LocalDateTime createdAt) {
}
