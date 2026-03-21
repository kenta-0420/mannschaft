package com.mannschaft.app.payment.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * Stripe Checkout セッション作成レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class CheckoutResponse {

    private final String checkoutUrl;
    private final String sessionId;
    private final LocalDateTime expiresAt;
}
