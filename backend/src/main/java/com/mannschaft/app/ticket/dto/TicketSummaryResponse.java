package com.mannschaft.app.ticket.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 顧客チケット横断サマリレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class TicketSummaryResponse {

    private final Long userId;
    private final String displayName;
    private final List<ActiveTicketItem> activeTickets;
    private final Integer totalRemaining;

    /**
     * ACTIVE なチケットの個別情報。
     */
    @Getter
    @RequiredArgsConstructor
    public static class ActiveTicketItem {
        private final Long bookId;
        private final String productName;
        private final Integer remaining;
        private final LocalDateTime expiresAt;
    }
}
