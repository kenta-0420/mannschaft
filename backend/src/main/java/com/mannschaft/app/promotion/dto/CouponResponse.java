package com.mannschaft.app.promotion.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * クーポンレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class CouponResponse {

    private final Long id;
    private final String scopeType;
    private final Long scopeId;
    private final Long createdBy;
    private final String title;
    private final String description;
    private final String couponType;
    private final BigDecimal discountValue;
    private final BigDecimal minPurchaseAmount;
    private final Integer maxIssues;
    private final Integer issuedCount;
    private final Short maxUsesPerUser;
    private final LocalDateTime validFrom;
    private final LocalDateTime validUntil;
    private final Boolean isActive;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
