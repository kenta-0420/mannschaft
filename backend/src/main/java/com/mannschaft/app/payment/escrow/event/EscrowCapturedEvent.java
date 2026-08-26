package com.mannschaft.app.payment.escrow.event;

import com.mannschaft.app.payment.escrow.EscrowSourceKind;

import java.util.UUID;

/**
 * F08.9 P1 Wave4: エスクロー取引が CAPTURED（capture 確定）になったことを表すドメインイベント
 * （設計書 F08.9 02 §1.1 / §4.2 状態機械「PAID 反映」）。
 *
 * <p><b>なぜ必要か（ドメイン境界の維持）:</b> 会費（{@link EscrowSourceKind#MEMBERSHIP}）の即時 charge は
 * Stripe の {@code payment_intent.succeeded} platform Webhook で CAPTURED 確定する
 * （{@link com.mannschaft.app.payment.escrow.EscrowWebhookService} の {@code applySucceeded}）。この CAPTURED を
 * 受けて {@code member_payments} を PENDING→PAID にする必要があるが、<b>escrow（F22.1 money rail）は
 * member_payments（F08.9 会費）を知らない</b>（モジュラーモノリスのドメイン境界・CLAUDE.md 原則1）。
 * そこで escrow 側は本イベントを発火するだけに留め、PAID 反映は payment(F08.9)側の
 * {@link com.mannschaft.app.payment.escrow.MembershipPaymentCaptureListener} が購読して行う。
 * これにより escrow が会費 Entity を直接参照する逆依存を作らず、escrow への改変を「イベント発火の最小限」に抑える。</p>
 *
 * <p><b>発火タイミングは AFTER_COMMIT 前提:</b> CAPTURED 確定（および ledger 起票）が durable になってから
 * リスナを起こすため、{@link com.mannschaft.app.payment.escrow.EscrowWebhookService} は本イベントを
 * Webhook 処理トランザクションのコミット後（{@code AFTER_COMMIT}）に配送する。リスナ側も
 * {@code TransactionalEventListener(AFTER_COMMIT)} で受ける。会費以外（RECRUITMENT 等）の CAPTURED でも
 * 発火しうるが、会費リスナは {@code sourceKind=MEMBERSHIP} のみ拾い、他は no-op で無視する。</p>
 *
 * <ul>
 *   <li>{@code escrowTransactionId} — CAPTURED になった {@code escrow_transactions.id}
 *       （{@code member_payments.escrow_transaction_id} との突合キー）。</li>
 *   <li>{@code sourceKind} — 出所種別。会費リスナはこれが {@link EscrowSourceKind#MEMBERSHIP} のときのみ処理する。</li>
 * </ul>
 */
public record EscrowCapturedEvent(
        UUID escrowTransactionId,
        EscrowSourceKind sourceKind) {
}
