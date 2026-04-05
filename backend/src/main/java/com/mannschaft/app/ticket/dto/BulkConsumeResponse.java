package com.mannschaft.app.ticket.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 複数チケット同時消化レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class BulkConsumeResponse {

    private final List<BulkConsumeResultItem> consumed;
    private final List<String> failed;

    /**
     * 一括消化の個別結果。
     */
    @Getter
    @RequiredArgsConstructor
    public static class BulkConsumeResultItem {
        private final Long bookId;
        private final Integer remainingTickets;
        private final String status;
    }
}
