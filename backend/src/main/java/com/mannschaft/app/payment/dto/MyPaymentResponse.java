package com.mannschaft.app.payment.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 自分の支払い状況レスポンスDTO。
 */
@Builder(toBuilder = true)
@Getter
public class MyPaymentResponse {

    Long id;
    PaymentItemSummary paymentItem;
    ScopeInfo scope;
    PaymentMoneyDto money;
    PaymentStatusDto statusInfo;
    PaymentReceiptDto receipt;

    public record PaymentMoneyDto(BigDecimal amountPaid, String currency) {}
    public record PaymentStatusDto(String status, LocalDate validFrom, LocalDate validUntil, LocalDateTime paidAt) {}
    public record PaymentReceiptDto(String receiptUrl, String paymentMethod) {}

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
