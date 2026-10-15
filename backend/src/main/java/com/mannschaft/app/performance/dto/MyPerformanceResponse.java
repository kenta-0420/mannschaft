package com.mannschaft.app.performance.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 自分のパフォーマンス（全チーム横断）レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class MyPerformanceResponse {

    private final Long teamId;
    private final String teamName;
    private final List<MetricSummary> metrics;

    @Getter
    @RequiredArgsConstructor
    public static class MetricSummary {
        private final Long metricId;
        private final String name;
        private final String unit;
        private final String aggregationType;
        private final BigDecimal total;
        private final int recordCount;
        private final BigDecimal targetValue;
        private final BigDecimal achievementRate;
        private final BigDecimal previousValue;
        private final BigDecimal latestValue;
        private final BigDecimal changeRate;
        private final LatestRecord latestRecord;
    }

    @Getter
    @RequiredArgsConstructor
    public static class LatestRecord {
        private final LocalDate recordedDate;
        private final BigDecimal value;
        private final String note;
    }
}
