package com.mannschaft.app.common.outbox;

/**
 * 通知 outbox の行の状態（docs/architecture/notification_outbox.md §3）。
 *
 * <ul>
 *   <li>{@link #PENDING}: 取り込み待ち（{@code next_attempt_at} を過ぎたら claim される）</li>
 *   <li>{@link #RELAYING}: relay が claim して取り込み中（{@code claim_token}・{@code claimed_at} が入る）</li>
 *   <li>{@link #RELAYED}: 通知ドメインへの取り込みが済んだ（{@code relayed_at} から7日で掃除）</li>
 *   <li>{@link #DEAD}: 上限回数まで失敗した（{@code dead_at} から30日で掃除）</li>
 * </ul>
 */
public enum OutboxStatus {
    PENDING,
    RELAYING,
    RELAYED,
    DEAD
}
