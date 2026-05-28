package com.mannschaft.app.payment.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支払い項目レスポンスDTO。
 */
@Builder(toBuilder = true)
@Getter
public class PaymentItemResponse {

    Long id;
    PaymentItemMetaDto meta;
    PaymentMoneyDto money;
    StripeIntegrationDto stripe;
    PaymentItemAuditDto audit;

    public record PaymentItemMetaDto(String name, String description, String type, Short displayOrder, Short gracePeriodDays) {}
    public record PaymentMoneyDto(BigDecimal amount, String currency) {}
    public record StripeIntegrationDto(String stripeProductId, String stripePriceId) {}
    public record PaymentItemAuditDto(Boolean isActive, LocalDateTime createdAt, LocalDateTime updatedAt) {}
}
