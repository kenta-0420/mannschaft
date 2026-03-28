package com.mannschaft.app.analytics.dto;

import com.mannschaft.app.analytics.Granularity;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 収益トレンドレスポンス。
 */
@Getter
@RequiredArgsConstructor
public class RevenueTrendResponse {

    private final Granularity granularity;
    private final List<TrendPoint> points;

    /**
     * 収益トレンドの各ポイント。
     */
    @Getter
    @RequiredArgsConstructor
    public static class TrendPoint {
        private final String period;
        private final BigDecimal grossRevenue;
        private final BigDecimal refundAmount;
        private final BigDecimal netRevenue;
        private final int transactionCount;
    }
}
