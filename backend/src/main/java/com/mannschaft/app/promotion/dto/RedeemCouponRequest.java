package com.mannschaft.app.promotion.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * クーポン利用リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class RedeemCouponRequest {

    private final String redemptionDetail;
}
