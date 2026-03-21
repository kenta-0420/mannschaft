package com.mannschaft.app.payment.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * コンテンツゲートレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ContentPaymentGateResponse {

    private final Long id;
    private final String contentType;
    private final Long contentId;
    private final Boolean isTitleHidden;
    private final PaymentItemDetail paymentItem;
    private final Long createdBy;
    private final LocalDateTime createdAt;

    /**
     * 支払い項目の詳細情報。
     */
    @Getter
    @RequiredArgsConstructor
    public static class PaymentItemDetail {
        private final Long id;
        private final String name;
        private final String type;
        private final BigDecimal amount;
        private final String currency;
    }
}
