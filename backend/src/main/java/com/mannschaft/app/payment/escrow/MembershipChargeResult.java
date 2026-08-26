package com.mannschaft.app.payment.escrow;

import java.util.UUID;

/**
 * F08.9 P1 Wave0: 会費（{@link EscrowSourceKind#MEMBERSHIP}）の即時 charge 結果（設計書 F08.9 02 §1.1）。
 *
 * <p>{@link ConnectChargeService#charge(MembershipChargeCommand)} の出力。即時モード
 * （{@link EscrowCaptureMode#AUTOMATIC}）の Destination PaymentIntent を作成し、払い手本人が Stripe.js で
 * confirm（カード直送・PCI SAQ-A・F22.1 03 §1）するために {@code clientSecret} を返す。</p>
 *
 * <p><b>確定（CAPTURED）と複式記帳は本結果の時点では未完了。</b> Stripe が PI を確認すると
 * {@code payment_intent.succeeded} platform Webhook（{@link EscrowWebhookService}）が CAPTURED 化と
 * ledger（CAPTURE/TRANSFER_OUT/FEE）の起票を冪等に行う（charge() では記帳しない・二重記帳防止）。
 * したがって本結果の {@code status} は通常 {@link EscrowStatus#AUTHORIZED}（succeeded webhook 待ち）。</p>
 *
 * <ul>
 *   <li>{@code escrowTransactionId} — 作成した {@code escrow_transactions} の ID（会費の {@code member_payments} 連結キー）。</li>
 *   <li>{@code clientSecret} — 払い手本人へのみ返す（他人へ漏らさない・PCI SAQ-A）。</li>
 *   <li>{@code paymentIntentId} — 作成した Destination PaymentIntent（{@code pi_xxx}）。</li>
 *   <li>{@code status} — 作成直後の escrow 状態（通常 {@link EscrowStatus#AUTHORIZED}・succeeded webhook で CAPTURED）。</li>
 * </ul>
 */
public record MembershipChargeResult(
        UUID escrowTransactionId,
        String clientSecret,
        String paymentIntentId,
        EscrowStatus status) {
}
