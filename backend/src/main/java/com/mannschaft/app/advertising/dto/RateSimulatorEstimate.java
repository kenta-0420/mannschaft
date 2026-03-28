package com.mannschaft.app.advertising.dto;

import java.math.BigDecimal;

/**
 * 料金シミュレーター見積もり結果。
 * <p>
 * リーチ推定値は展開済み（reachMin, reachMax, reachLabel）。
 */
public record RateSimulatorEstimate(
        BigDecimal totalCost,
        BigDecimal taxAmount,
        BigDecimal totalWithTax,
        BigDecimal dailyCost,
        Integer dailyImpressions,
        Integer estimatedClicks,
        BigDecimal estimatedCtr,
        Integer reachMin,
        Integer reachMax,
        String reachLabel
) {
}
