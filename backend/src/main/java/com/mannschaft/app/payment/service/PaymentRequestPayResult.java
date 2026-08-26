package com.mannschaft.app.payment.service;

import java.util.UUID;

/**
 * F08.9 P7: 協会請求支払いの結果（{@link PaymentRequestService#pay}）。
 *
 * <p>払い手本人（チーム ADMIN）が Stripe.js で confirm（カード直送・PCI SAQ-A）するための
 * {@code clientSecret} を含む。立替記録（team_payment_advances）の ID も返し、チーム精算フローへつなぐ。</p>
 *
 * <ul>
 *   <li>{@code paymentRequestId} — 支払った協会請求の ID。</li>
 *   <li>{@code escrowTransactionId} — 連結した escrow 取引 ID（money rail）。</li>
 *   <li>{@code advanceId} — 起票した立替/精算記録（team_payment_advances）の ID。</li>
 *   <li>{@code clientSecret} — 払い手本人へのみ返す（PCI SAQ-A）。冪等再支払い時は {@code null}。</li>
 * </ul>
 */
public record PaymentRequestPayResult(
        UUID paymentRequestId,
        UUID escrowTransactionId,
        UUID advanceId,
        String clientSecret) {
}
