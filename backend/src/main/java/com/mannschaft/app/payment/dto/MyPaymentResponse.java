package com.mannschaft.app.payment.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 自分の支払い状況レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class MyPaymentResponse {

    private final Long id;
    private final PaymentItemSummary paymentItem;
    private final ScopeInfo scope;
    private final BigDecimal amountPaid;
    private final String currency;
    private final String paymentMethod;
    private final String status;
    private final LocalDate validFrom;
    private final LocalDate validUntil;
    private final LocalDateTime paidAt;
    private final String receiptUrl;

    /**
     * 支払い項目の要約情報。
     */
    @Getter
    @RequiredArgsConstructor
    public static class PaymentItemSummary {
        private final Long id;
        private final String name;
        private final String type;
        private final BigDecimal amount;
        private final String currency;
    }

    /**
     * スコープ情報（チームまたは組織）。
     */
    @Getter
    @RequiredArgsConstructor
    public static class ScopeInfo {
        private final String type;
        private final Long id;
        private final String name;
    }
}
