package com.mannschaft.app.ticket.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 回数券レスポンスDTO。
 */
@Builder(toBuilder = true)
@Getter
public class TicketBookResponse {

    Long id;
    Long userId;
    String userName;
    String productName;
    TicketQuantityDto quantity;
    TicketStatusDto status;
    NoteDto note;
    BookAuditDto audit;

    public record TicketQuantityDto(Integer totalTickets, Integer usedTickets, Integer remainingTickets) {}

    public record TicketStatusDto(String status, LocalDateTime purchasedAt, LocalDateTime expiresAt,
                                  Long daysUntilExpiry) {}

    public record NoteDto(String note) {}

    public record BookAuditDto(LocalDateTime createdAt, LocalDateTime updatedAt) {}
}
