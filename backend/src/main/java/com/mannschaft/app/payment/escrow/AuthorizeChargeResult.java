package com.mannschaft.app.payment.escrow;

import java.util.UUID;

/**
 * F22.1 統一決済 P2-b: 謝礼の与信（authorize）結果（設計書 02 §5.1 / §8）。
 *
 * <p><b>第一陣 status 意味論の根治（2026-06-10）:</b> 受取側 onboarding 完了時は PaymentIntent（manual-capture）を
 * 作成して {@code status=PENDING_CONFIRMATION}（PI 作成済・札主未 confirm）で返し、{@code clientSecret} を支払者本人へ
 * 返す。支払者本人が Stripe.js で confirm（カード直送・PCI SAQ-A・設計書 03 §1）すると Stripe 上に真の与信が立ち、
 * {@code payment_intent.amount_capturable_updated} webhook で escrow が {@link EscrowStatus#AUTHORIZED} へ昇格する。
 * {@code status=HELD}（受取側 onboarding 未完了）の場合 PaymentIntent は未作成のため
 * {@code clientSecret}/{@code paymentIntentId} は null。</p>
 *
 * <ul>
 *   <li>{@code escrowId} — 作成した escrow_transactions の ID。</li>
 *   <li>{@code status} — {@link EscrowStatus#PENDING_CONFIRMATION}（onboarding 完了・confirm 待ち）または
 *       {@link EscrowStatus#HELD}（onboarding 未完了）。冪等再取得時は既存行の status をそのまま返す。</li>
 *   <li>{@code clientSecret} — PENDING_CONFIRMATION 時のみ非 null。支払者本人へのみ返す（他人へ漏らさない）。</li>
 *   <li>{@code paymentIntentId} — PENDING_CONFIRMATION 時のみ（{@code pi_xxx}）。HELD は null。</li>
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
