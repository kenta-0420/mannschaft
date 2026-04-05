package com.mannschaft.app.advertising.dto;

import java.math.BigDecimal;

/**
 * 料金比較項目（シミュレーター用）。
 */
public record RateComparisonItem(
        String prefecture,
        String template,
        BigDecimal unitPrice,
        BigDecimal totalCost,
        String label
) {
}
