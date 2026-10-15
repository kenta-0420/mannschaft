package com.mannschaft.app.analytics.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 解約分析レスポンス。
 */
@Getter
@RequiredArgsConstructor
public class ChurnAnalysisResponse {

    private final List<ChurnPoint> points;

    /**
     * 月別解約データポイント。
     */
    @Getter
    @RequiredArgsConstructor
    public static class ChurnPoint {
        private final String month;
        private final BigDecimal userChurnRate;
        private final BigDecimal revenueChurnRate;
        private final int churnedUsers;
        private final BigDecimal churnedMrr;
        private final BigDecimal expansionMrr;
        private final BigDecimal netMrrChurnRate;
    }
}
