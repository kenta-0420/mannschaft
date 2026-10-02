package com.mannschaft.app.payment.service;

import java.util.UUID;

/**
 * F08.9 P7: 協会請求支払い開始の結果（{@link PaymentRequestPaymentCoordinator#pay}）。
 *
 * <p>払い手本人（チーム ADMIN）が Stripe.js で confirm（カード直送・PCI SAQ-A）するための
 * {@code clientSecret} を含む。立替記録（team_payment_advances）は成功 webhook で起票する。</p>
 *
 * <ul>
 *   <li>{@code paymentRequestId} — 支払った協会請求の ID。</li>
 *   <li>{@code escrowTransactionId} — 連結した escrow 取引 ID（money rail）。</li>
 *   <li>{@code advanceId} — 支払い開始時は {@code null}。成功 webhook 後に別途起票される。</li>
 *   <li>{@code clientSecret} — 払い手本人へのみ返す（PCI SAQ-A）。冪等再支払い時は {@code null}。</li>
 * </ul>
 */
public record PaymentRequestPayResult(
        UUID paymentRequestId,
        UUID escrowTransactionId,
        UUID advanceId,
        String clientSecret) {
}
