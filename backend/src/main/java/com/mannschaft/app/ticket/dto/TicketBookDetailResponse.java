package com.mannschaft.app.ticket.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 回数券詳細レスポンスDTO。消化履歴・決済情報を含む。
 */
@Getter
@RequiredArgsConstructor
public class TicketBookDetailResponse {

    private final Long id;
    private final String productName;
    private final Integer totalTickets;
    private final Integer usedTickets;
    private final Integer remainingTickets;
    private final String status;
    private final LocalDateTime purchasedAt;
    private final LocalDateTime expiresAt;
    private final Long daysUntilExpiry;
    private final String note;
    private final PaymentSummary payment;
    private final List<ConsumptionResponse> consumptions;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    /**
     * 決済概要。
     */
    @Getter
    @RequiredArgsConstructor
    public static class PaymentSummary {
        private final String paymentMethod;
        private final Integer amount;
        private final String status;
        private final LocalDateTime paidAt;
    }
}
