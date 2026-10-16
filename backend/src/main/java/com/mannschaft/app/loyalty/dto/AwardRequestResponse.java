package com.mannschaft.app.loyalty.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.mannschaft.app.loyalty.RequestReason;
import com.mannschaft.app.loyalty.RequestStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/** チームポイントの業務契約。 */
@Schema(name = "LoyaltyAwardRequestResponse")
public record AwardRequestResponse(
        UUID id,
        Long teamId,
        String teamName,
        String applicantDisplayName,
        String locationName,
        String ruleName,
        int points,
        LocalDate awardOn,
        String verificationCode,
        RequestStatus status,
        RequestReason reason,
        long version,
        LocalDateTime createdAt,
        LocalDateTime expiresAt,
        LocalDateTime closedAt,
        UUID transactionId,
        boolean canApprove,
        boolean canCancel) {
}
