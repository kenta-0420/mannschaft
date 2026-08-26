package com.mannschaft.app.payment.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
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
    /** F08.9 P6: 期別有効期間（type=TERM のみ設定）。 */
    TermPeriodDto term;

    public record PaymentItemMetaDto(String name, String description, String type, Short displayOrder, Short gracePeriodDays) {}
    public record PaymentMoneyDto(BigDecimal amount, String currency) {}
    public record StripeIntegrationDto(String stripeProductId, String stripePriceId) {}
    public record PaymentItemAuditDto(Boolean isActive, LocalDateTime createdAt, LocalDateTime updatedAt) {}
    /** F08.9 P6: 期別有効期間。type=TERM の場合のみ設定される。 */
    public record TermPeriodDto(LocalDate termStartsOn, LocalDate termEndsOn) {}
}
