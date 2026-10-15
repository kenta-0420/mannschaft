package com.mannschaft.app.analytics.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 予測レスポンス。
 */
@Getter
@RequiredArgsConstructor
public class ForecastResponse {

    private final LocalDate baseDate;
    private final BigDecimal currentMrr;
    private final int currentUserCount;
    private final List<ForecastPoint> forecast;
    private final ForecastAssumptions assumptions;

    /**
     * 予測の各月ポイント。
     */
    @Getter
    @RequiredArgsConstructor
    public static class ForecastPoint {
        private final String month;
        private final BigDecimal projectedMrr;
        private final int projectedUsers;
        private final BigDecimal confidenceLow;
        private final BigDecimal confidenceHigh;
    }

    /**
     * 予測の前提条件。
     */
    @Getter
    @RequiredArgsConstructor
    public static class ForecastAssumptions {
        private final BigDecimal monthlyGrowthRate;
        private final BigDecimal churnRate;
        private final String method;
    }
}
