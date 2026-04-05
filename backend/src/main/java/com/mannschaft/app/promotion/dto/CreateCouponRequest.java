package com.mannschaft.app.promotion.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * クーポン作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateCouponRequest {

    @NotBlank
    @Size(max = 200)
    private final String title;

    private final String description;

    @NotBlank
    @Size(max = 20)
    private final String couponType;

    private final BigDecimal discountValue;

    private final BigDecimal minPurchaseAmount;

    private final Integer maxIssues;

    private final Short maxUsesPerUser;

    @NotNull
    private final LocalDateTime validFrom;

    @NotNull
    private final LocalDateTime validUntil;
}
