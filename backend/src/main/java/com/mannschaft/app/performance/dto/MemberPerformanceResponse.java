package com.mannschaft.app.performance.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 特定メンバーのパフォーマンスレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class MemberPerformanceResponse {

    private final Long userId;
    private final String displayName;
    private final TeamStatsResponse.Period period;
    private final List<MetricDetail> metrics;

    @Getter
    @RequiredArgsConstructor
    public static class MetricDetail {
        private final Long metricId;
        private final String name;
        private final String unit;
        private final String aggregationType;
        private final BigDecimal total;
        private final BigDecimal avg;
        private final BigDecimal max;
        private final BigDecimal min;
        private final int recordCount;
        private final BigDecimal targetValue;
        private final BigDecimal achievementRate;
        private final BigDecimal previousValue;
        private final BigDecimal latestValue;
        private final BigDecimal changeRate;
        private final PersonalBest personalBest;
        private final List<MonthlyTrend> trend;
    }

    @Getter
    @RequiredArgsConstructor
    public static class PersonalBest {
        private final BigDecimal value;
        private final LocalDate recordedDate;
    }

    @Getter
    @RequiredArgsConstructor
    public static class MonthlyTrend {
        private final String month;
        private final BigDecimal value;
    }
}
