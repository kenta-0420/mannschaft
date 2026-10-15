package com.mannschaft.app.analytics.dto;

import com.mannschaft.app.analytics.Granularity;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 広告分析レスポンス。
 */
@Getter
@RequiredArgsConstructor
public class AdAnalyticsResponse {

    private final Granularity granularity;
    private final AdSummary summary;
    private final List<AdPoint> points;

    /**
     * 広告サマリー。
     */
    @Getter
    @RequiredArgsConstructor
    public static class AdSummary {
        private final long totalImpressions;
        private final long totalClicks;
        private final BigDecimal avgCtr;
        private final BigDecimal totalRevenue;
        private final BigDecimal avgEcpm;
    }

    /**
     * 広告の各期間ポイント。
     */
    @Getter
    @RequiredArgsConstructor
    public static class AdPoint {
        private final String period;
        private final long impressions;
        private final long clicks;
        private final BigDecimal ctr;
        private final BigDecimal revenue;
        private final BigDecimal ecpm;
    }
}
