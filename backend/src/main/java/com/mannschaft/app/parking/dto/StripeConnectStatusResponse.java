package com.mannschaft.app.parking.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Stripe Connect ステータスレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class StripeConnectStatusResponse {

    private final Long userId;
    private final String stripeAccountId;
    private final Boolean chargesEnabled;
    private final Boolean payoutsEnabled;
    private final Boolean onboardingCompleted;
}
