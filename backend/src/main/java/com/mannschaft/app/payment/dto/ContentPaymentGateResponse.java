package com.mannschaft.app.payment.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * コンテンツゲートレスポンスDTO。
 */
@Builder(toBuilder = true)
@Getter
public class ContentPaymentGateResponse {

    Long id;
    ContentIdentifierDto content;
    PaymentItemDetail paymentItem;
    GateAuditDto audit;

    public record ContentIdentifierDto(String contentType, Long contentId, Boolean isTitleHidden) {}
    public record GateAuditDto(Long createdBy, LocalDateTime createdAt) {}

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
