package com.mannschaft.app.promotion.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 課金記録レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class BillingRecordResponse {

    private final Long id;
    private final Long promotionId;
    private final String scopeType;
    private final Long scopeId;
    private final Integer deliveryCount;
    private final BigDecimal unitPrice;
    private final BigDecimal totalAmount;
    private final String billingStatus;
    private final String stripeChargeId;
    private final LocalDateTime billedAt;
    private final LocalDateTime createdAt;
}
