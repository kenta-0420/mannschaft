package com.mannschaft.app.advertising.dto;

import com.mannschaft.app.advertising.PricingModel;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 広告料金カードレスポンス。
 */
public record AdRateCardResponse(
        Long id,
        String targetPrefecture,
        String targetTemplate,
        PricingModel pricingModel,
        BigDecimal unitPrice,
        BigDecimal minDailyBudget,
        LocalDate effectiveFrom,
        LocalDate effectiveUntil
) {
}
