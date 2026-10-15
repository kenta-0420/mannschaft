package com.mannschaft.app.payment.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 未払い項目レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class PaymentRequirementResponse {

    private final MyPaymentResponse.ScopeInfo scope;
    private final String requirementType;
    private final PaymentItemRequirement paymentItem;
    private final boolean isOverdue;
    private final LocalDate overdueSince;

    /**
     * 支払い項目情報（Checkout 導線用に stripe_price_id を含む）。
     */
    @Getter
    @RequiredArgsConstructor
    public static class PaymentItemRequirement {
        private final Long id;
        private final String name;
        private final String type;
        private final BigDecimal amount;
        private final String currency;
        private final String stripePriceId;
        private final Short gracePeriodDays;
    }
}
