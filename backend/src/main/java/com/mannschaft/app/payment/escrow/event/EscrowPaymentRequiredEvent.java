package com.mannschaft.app.payment.escrow.event;

import java.util.UUID;

/**
 * HELD escrow が昇格し、札主の決済確認（confirm）待ちになったことを表す業務イベント（Issue #2990 L7）。
 *
 * <p>{@code EscrowLifecycleService#promoteHeldEscrow} の業務トランザクション（Stripe PaymentIntent 作成 ＋
 * {@code escrow_transactions} の PENDING_CONFIRMATION 化）の内側で publish し、札主への決済確認依頼通知は
 * {@link EscrowLifecycleNotificationListener}（{@code AFTER_COMMIT} ＋ {@code @Async("event-pool")}）が送る。</p>
 *
 * @param escrowId 昇格後 escrow の ID
 */
public record EscrowPaymentRequiredEvent(UUID escrowId) {
}
