package com.mannschaft.app.payment.escrow;

/**
 * F03.11.1 募集キャンセル料の徴収結果（設計書 §3.4）。
 *
 * <p>{@code stripeReference} は紛争対応で Stripe ダッシュボードから直接引ける識別子であり、
 * 部分キャプチャなら PaymentIntent ID（{@code pi_...}）、差額返金なら Refund ID（{@code re_...}）が入る（§3.7）。
 * 徴収できなかった場合は {@code uncollectedAmount} に残った額（円）が入る。</p>
 *
 * @param outcome          徴収の種別
 * @param stripeReference  Stripe の参照 ID（徴収できなかった場合は null）
 * @param uncollectedAmount 徴収できなかった額（成功時は 0）
 */
public record SettleCancellationFeeResult(
        SettleCancellationFeeOutcome outcome,
        String stripeReference,
        long uncollectedAmount) {
}
