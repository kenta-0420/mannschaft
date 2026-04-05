package com.mannschaft.app.performance.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * パフォーマンス記録レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class RecordResponse {

    private final Long id;
    private final Long metricId;
    private final String metricName;
    private final Long userId;
    private final Long scheduleId;
    private final Long activityResultId;
    private final LocalDate recordedDate;
    private final BigDecimal value;
    private final String unit;
    private final String note;
    private final String source;
    private final Long recordedBy;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
