package com.mannschaft.app.loyalty.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.mannschaft.app.loyalty.RequestReason;
import com.mannschaft.app.loyalty.RequestStatus;
import java.time.LocalDate;
import java.time.Instant;
import java.util.UUID;

/** チームポイントの業務契約。 */
@Schema(name = "LoyaltyAwardRequestResponse")
public record AwardRequestResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        Long teamId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String teamName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String applicantDisplayName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String locationName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String ruleName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        int points,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        LocalDate awardOn,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String verificationCode,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        RequestStatus status,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        RequestReason reason,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        long version,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        Instant createdAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        Instant expiresAt,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        Instant closedAt,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        UUID transactionId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        boolean canApprove,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        boolean canCancel) {
}
