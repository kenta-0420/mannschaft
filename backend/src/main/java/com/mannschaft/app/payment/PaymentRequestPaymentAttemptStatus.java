package com.mannschaft.app.payment;

/** Stripe webhook によってのみ終端化する請求決済試行の状態。 */
public enum PaymentRequestPaymentAttemptStatus {
    CREATING,
    REQUIRES_ACTION,
    SUCCEEDED,
    FAILED
}
