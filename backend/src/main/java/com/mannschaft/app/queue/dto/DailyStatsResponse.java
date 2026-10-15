package com.mannschaft.app.queue.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 日次統計レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class DailyStatsResponse {

    private final Long id;
    private final String scopeType;
    private final Long scopeId;
    private final Long counterId;
    private final LocalDate statDate;
    private final Short totalTickets;
    private final Short completedCount;
    private final Short cancelledCount;
    private final Short noShowCount;
    private final BigDecimal avgWaitMinutes;
    private final BigDecimal avgServiceMinutes;
    private final Short peakHour;
    private final Short qrCount;
    private final Short onlineCount;
}
