package com.mannschaft.app.payment.dto;

import com.mannschaft.app.payment.AdvanceSettlementStatus;
import com.mannschaft.app.payment.entity.TeamPaymentAdvanceEntity;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * F08.9 P7: 協会請求の立替/精算記録レスポンス DTO（案3・02_api §9）。
 *
 * <p>casing は camelCase で Entity と 1:1。チーム ADMIN の立替/精算一覧で使う。</p>
 */
@Builder
public record TeamPaymentAdvanceResponse(
        UUID id,
        Long organizationId,
        Long teamId,
        Long payerUserId,
        UUID escrowTransactionId,
        UUID paymentRequestId,
        Integer advancedAmount,
        String currency,
        LocalDateTime advancedAt,
        AdvanceSettlementStatus settlementStatus,
        LocalDateTime settledAt,
        Long settledConfirmedBy) {

    /**
     * Entity を DTO へ写像する。
     */
    public static TeamPaymentAdvanceResponse from(TeamPaymentAdvanceEntity e) {
        return TeamPaymentAdvanceResponse.builder()
                .id(e.getId())
                .organizationId(e.getOrganizationId())
                .teamId(e.getTeamId())
                .payerUserId(e.getPayerUserId())
                .escrowTransactionId(e.getEscrowTransactionId())
                .paymentRequestId(e.getPaymentRequestId())
                .advancedAmount(e.getAdvancedAmount())
                .currency(e.getCurrency())
                .advancedAt(e.getAdvancedAt())
                .settlementStatus(e.getSettlementStatus())
                .settledAt(e.getSettledAt())
                .settledConfirmedBy(e.getSettledConfirmedBy())
                .build();
    }
}
