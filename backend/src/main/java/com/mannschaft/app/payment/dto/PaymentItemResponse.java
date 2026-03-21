package com.mannschaft.app.payment.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支払い項目レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class PaymentItemResponse {

    private final Long id;
    private final String name;
    private final String description;
    private final String type;
    private final BigDecimal amount;
    private final String currency;
    private final String stripeProductId;
    private final String stripePriceId;
    private final Boolean isActive;
    private final Short displayOrder;
    private final Short gracePeriodDays;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
