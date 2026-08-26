package com.mannschaft.app.ticket.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 回数券商品レスポンスDTO。
 */
@Builder(toBuilder = true)
@Getter
public class TicketProductResponse {

    Long id;
    ProductMetaDto meta;
    ProductPricingDto pricing;
    StripeIntegrationDto stripe;
    ProductDisplayDto display;
    ProductAuditDto audit;

    public record ProductMetaDto(String name, String description, Integer totalTickets, Integer sortOrder) {}

    public record ProductPricingDto(Integer price, Integer priceExcludingTax, BigDecimal taxRate,
                                    Integer validityDays) {}

    public record StripeIntegrationDto(String stripeProductId, String stripePriceId) {}

    public record ProductDisplayDto(String imageUrl, Boolean isOnlinePurchasable, Boolean isActive) {}

    public record ProductAuditDto(LocalDateTime createdAt, LocalDateTime updatedAt, LocalDateTime deletedAt) {}
}
