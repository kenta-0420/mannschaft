package com.mannschaft.app.ticket.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 回数券レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class TicketBookResponse {

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
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
