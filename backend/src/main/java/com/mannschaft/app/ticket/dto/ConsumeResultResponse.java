package com.mannschaft.app.ticket.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * チケット消化結果レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ConsumeResultResponse {

    private final Long consumptionId;
    private final Long bookId;
    private final Integer remainingTickets;
    private final String status;
    private final LocalDateTime consumedAt;
}
