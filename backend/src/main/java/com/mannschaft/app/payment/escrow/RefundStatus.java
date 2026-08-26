package com.mannschaft.app.payment.escrow;

/**
 * F22.1 謝礼決済: 返金の状態。
 *
 * <p>{@code refunds.status}（VARCHAR(12) + CHECK）に対応する。
 * Stripe {@code charge.refunded} Webhook で確定する。</p>
 */
public enum RefundStatus {
    PENDING,
    SUCCEEDED,
    FAILED
}
