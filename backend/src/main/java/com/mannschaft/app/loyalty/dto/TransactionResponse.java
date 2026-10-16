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
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        Long teamId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        TransactionKind kind,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        TransactionChannel channel,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        int delta,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        int balanceAfter,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        UUID ruleId,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        UUID rewardId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String label,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        LocalDate awardOn,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        LocalDateTime createdAt) {
}
