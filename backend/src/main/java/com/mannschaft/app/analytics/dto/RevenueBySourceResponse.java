package com.mannschaft.app.analytics.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 収益源別内訳レスポンス。
 */
@Getter
@RequiredArgsConstructor
public class RevenueBySourceResponse {

    private final PeriodRange period;
    private final List<SourceBreakdown> sources;
    private final BigDecimal totalNetRevenue;

    /**
     * 集計期間の範囲。
     */
    @Getter
    @RequiredArgsConstructor
    public static class PeriodRange {
        private final LocalDate from;
        private final LocalDate to;
    }

    /**
     * 収益源別の内訳。
     */
    @Getter
    @RequiredArgsConstructor
    public static class SourceBreakdown {
        private final String source;
        private final BigDecimal netRevenue;
        private final BigDecimal percentage;
        private final int transactionCount;
    }
}
