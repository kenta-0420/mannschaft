package com.mannschaft.app.ticket.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ダッシュボードウィジェット用チケット残数サマリレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class TicketWidgetResponse {

    private final Integer activeCount;
    private final List<TicketWidgetItem> tickets;

    /**
     * ウィジェット内の個別チケット情報。
     */
    @Getter
    @RequiredArgsConstructor
    public static class TicketWidgetItem {
        private final Long bookId;
        private final String productName;
        private final Integer remainingTickets;
        private final LocalDateTime expiresAt;
        private final Long daysUntilExpiry;
        private final String urgency;
    }
}
