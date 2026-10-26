package com.mannschaft.app.analytics.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 収益サマリーレスポンス。
 */
@Getter
@RequiredArgsConstructor
public class RevenueSummaryResponse {

    private final LocalDate date;
    private final BigDecimal mrr;
    private final BigDecimal mrrGrowthRate;
    private final BigDecimal arr;
    private final BigDecimal arpu;
    private final BigDecimal arpuPayingOnly;
    private final BigDecimal ltv;
    private final int payingUsers;
    private final int totalActiveUsers;
    private final BigDecimal payingRatio;
    private final BigDecimal nrr;
    private final BigDecimal quickRatio;
    private final BigDecimal paybackMonths;
    private final ComparisonData comparison;
    private final StripeReconciliation stripeReconciliation;

    /**
     * 前月・前年比較データ。
     */
    @Getter
    @RequiredArgsConstructor
    public static class ComparisonData {
        private final BigDecimal mrrPrevMonth;
        private final BigDecimal mrrChangePct;
        private final BigDecimal mrrYoyChangePct;
        private final BigDecimal arpuPrevMonth;
        private final BigDecimal arpuChangePct;
        private final BigDecimal arpuYoyChangePct;
        private final BigDecimal nrrPrevMonth;
        private final BigDecimal quickRatioPrevMonth;
    }

    /**
     * Stripe突合データ。
     */
    @Getter
    @RequiredArgsConstructor
    public static class StripeReconciliation {
        private final LocalDate lastReconciledDate;
        private final String status;
        private final BigDecimal discrepancyAmount;
        private final BigDecimal discrepancyPct;
    }
}
