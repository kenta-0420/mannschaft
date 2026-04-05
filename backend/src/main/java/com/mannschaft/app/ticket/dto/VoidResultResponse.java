package com.mannschaft.app.ticket.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 消化取消結果レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class VoidResultResponse {

    private final Long consumptionId;
    private final Boolean isVoided;
    private final LocalDateTime voidedAt;
    private final Integer bookRemainingTickets;
}
