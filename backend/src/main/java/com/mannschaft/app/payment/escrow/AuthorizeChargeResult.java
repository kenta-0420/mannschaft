package com.mannschaft.app.payment.escrow;

import java.util.UUID;

/**
 * F22.1 統一決済 P2-b: 謝礼の与信（authorize）結果（設計書 02 §5.1 / §8）。
 *
 * <p>{@code clientSecret} は受取側 onboarding 完了（{@code status=AUTHORIZED}）時のみ非 null で、
 * 支払者本人が Stripe.js で confirm（カード直送・PCI SAQ-A・設計書 03 §1）するために返す。
 * {@code status=HELD}（受取側 onboarding 未完了）の場合 PaymentIntent は未作成のため
 * {@code clientSecret}/{@code paymentIntentId} は null。</p>
 *
 * <ul>
 *   <li>{@code escrowId} — 作成した escrow_transactions の ID。</li>
 *   <li>{@code status} — {@link EscrowStatus#AUTHORIZED} または {@link EscrowStatus#HELD}。</li>
 *   <li>{@code clientSecret} — AUTHORIZED 時のみ。支払者本人へのみ返す（他人へ漏らさない）。</li>
 *   <li>{@code paymentIntentId} — AUTHORIZED 時のみ（{@code pi_xxx}）。</li>
 *   <li>{@code faceAmount}/{@code chargeAmount}/{@code applicationFeeAmount} — 手数料折半の内訳（円整数）。</li>
 * </ul>
 */
public record AuthorizeChargeResult(
        UUID escrowId,
        EscrowStatus status,
        String clientSecret,
        String paymentIntentId,
        long faceAmount,
        long chargeAmount,
        long applicationFeeAmount) {
}
