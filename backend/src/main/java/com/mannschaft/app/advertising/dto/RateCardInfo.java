package com.mannschaft.app.advertising.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 料金カード情報（シミュレーター用）。
 */
public record RateCardInfo(
        BigDecimal unitPrice,
        String unitLabel,
        BigDecimal minDailyBudget,
        LocalDate effectiveFrom
) {
}
