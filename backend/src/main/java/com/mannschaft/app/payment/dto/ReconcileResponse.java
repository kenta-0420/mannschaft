package com.mannschaft.app.payment.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Stripe 手動再同期レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ReconcileResponse {

    private final Long paymentId;
    private final String previousStatus;
    private final String currentStatus;
    private final String stripeStatus;
    private final boolean reconciled;
}
