package com.mannschaft.app.ticket.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 回数券商品レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class TicketProductResponse {

    private final Long id;
    private final String name;
    private final String description;
    private final Integer totalTickets;
    private final Integer price;
    private final Integer priceExcludingTax;
    private final BigDecimal taxRate;
    private final Integer validityDays;
    private final Boolean isOnlinePurchasable;
    private final String stripeProductId;
    private final String stripePriceId;
    private final String imageUrl;
    private final Boolean isActive;
    private final Integer sortOrder;
    private final LocalDateTime deletedAt;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
