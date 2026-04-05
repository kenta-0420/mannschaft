package com.mannschaft.app.advertising.dto;

import com.mannschaft.app.advertising.PricingModel;

import java.math.BigDecimal;

/**
 * 広告料金カード公開レスポンス（広告主向け、id等を含まない）。
 */
public record PublicRateCardResponse(
        String targetPrefecture,
        String targetTemplate,
        PricingModel pricingModel,
        BigDecimal unitPrice,
        BigDecimal minDailyBudget,
        String label
) {
}
