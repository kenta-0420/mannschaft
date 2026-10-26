package com.mannschaft.app.ticket.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * チケット消化レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ConsumptionResponse {

    private final Long id;
    private final Long bookId;
    private final LocalDateTime consumedAt;
    private final String note;
    private final Boolean isVoided;
    private final LocalDateTime voidedAt;
}
