package com.mannschaft.app.performance.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * チーム統計ダッシュボードレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class TeamStatsResponse {

    private final List<MetricStats> metrics;
    private final Period period;

    @Getter
    @RequiredArgsConstructor
    public static class MetricStats {
        private final Long metricId;
        private final String name;
        private final String unit;
        private final String aggregationType;
        private final BigDecimal teamTotal;
        private final BigDecimal teamAvg;
        private final BigDecimal targetValue;
        private final BigDecimal achievementRate;
        private final List<RankingEntry> ranking;
    }

    @Getter
    @RequiredArgsConstructor
    public static class RankingEntry {
        private final int rank;
        private final Long userId;
        private final String displayName;
        private final BigDecimal value;
    }

    @Getter
    @RequiredArgsConstructor
    public static class Period {
        private final LocalDate from;
        private final LocalDate to;
    }
}
