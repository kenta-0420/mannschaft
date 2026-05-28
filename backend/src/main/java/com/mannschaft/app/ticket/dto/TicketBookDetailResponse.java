package com.mannschaft.app.ticket.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 回数券詳細レスポンスDTO。消化履歴・決済情報を含む。
 */
@Builder(toBuilder = true)
@Getter
public class TicketBookDetailResponse {

    Long id;
    String productName;
    TicketQuantityDto quantity;
    TicketStatusDto status;
    PaymentSummary payment;
    List<ConsumptionResponse> consumptions;
    DetailAuditDto audit;

    public record TicketQuantityDto(Integer totalTickets, Integer usedTickets, Integer remainingTickets) {}

    public record TicketStatusDto(String status, LocalDateTime purchasedAt, LocalDateTime expiresAt,
                                  Long daysUntilExpiry) {}

    public record DetailAuditDto(String note, LocalDateTime createdAt, LocalDateTime updatedAt) {}

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
