package com.mannschaft.app.payment.dto;

/** Webhook 確定を追跡する、払い手本人向けの最小チェックアウト状態。 */
public record ConnectCheckoutStatusResponse(Long memberPaymentId, String status) {
}
