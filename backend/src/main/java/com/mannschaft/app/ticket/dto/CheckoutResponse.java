package com.mannschaft.app.ticket.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * Stripe Checkout Session レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class CheckoutResponse {

    private final String checkoutUrl;
    private final String sessionId;
    private final LocalDateTime expiresAt;
}
