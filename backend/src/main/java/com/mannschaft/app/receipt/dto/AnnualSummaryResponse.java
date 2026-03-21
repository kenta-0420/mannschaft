package com.mannschaft.app.receipt.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 年間サマリーレスポンスDTO。
 */
@Getter
@Builder
public class AnnualSummaryResponse {
    private final Integer year;
    private final BigDecimal totalAmount;
    private final Integer totalCount;
    private final BigDecimal totalTaxAmount;
    private final Map<String, TaxRateSummary> byTaxRate;
    private final List<ScopeSummary> byScope;
    private final Integer voidedCount;
    private final BigDecimal voidedAmount;

    /**
     * 税率別サマリー。
     */
    @Getter
    @Builder
    public static class TaxRateSummary {
        private final BigDecimal amountExclTax;
        private final BigDecimal taxAmount;
        private final Integer count;
    }

    /**
     * スコープ別サマリー。
     */
    @Getter
    @Builder
    public static class ScopeSummary {
        private final String scopeType;
        private final Long scopeId;
        private final String scopeName;
        private final BigDecimal totalAmount;
        private final Integer count;
    }
}
