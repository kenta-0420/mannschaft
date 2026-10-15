package com.mannschaft.app.promotion.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * ユーザー保有クーポンレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class UserCouponResponse {

    private final Long distributionId;
    private final Long couponId;
    private final String title;
    private final String description;
    private final String couponType;
    private final BigDecimal discountValue;
    private final String status;
    private final LocalDateTime distributedAt;
    private final LocalDateTime expiresAt;
}
