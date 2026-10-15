package com.mannschaft.app.advertising.dto;

import com.mannschaft.app.advertising.PricingModel;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 広告料金カード作成リクエスト。
 */
public record CreateAdRateCardRequest(

        String targetPrefecture,

        String targetTemplate,

        @NotNull
        PricingModel pricingModel,

        @NotNull
        @DecimalMin(value = "0", inclusive = false)
        BigDecimal unitPrice,

        @NotNull
        @DecimalMin(value = "100")
        BigDecimal minDailyBudget,

        @NotNull
        @FutureOrPresent
        LocalDate effectiveFrom
) {
}
