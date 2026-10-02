package com.mannschaft.app.notification.credit.event;

/**
 * F09.13 無料通知枠 90% 超過アラートの通知配送要求イベント（Issue #2990 / L2 ROLLBACK_COUPLED 是正）。
 *
 * <p>{@code NotificationCreditService#consume} が「今月ぶんのアラートを送る」と決めた
 * （{@code alert_sent_this_month} を立てた）業務トランザクションの内側で publish し、
 * {@link NotificationCreditFreeQuotaAlertListener} が {@code AFTER_COMMIT} で受け取る。</p>
 *
 * <p>イベントには<b>読み直せる ID のみ</b>を載せる。受信者（組織 ADMIN）の解決も本文の組み立ても
 * 配送リスナー側で業務トランザクションの外で行う。</p>
 *
 * @param organizationId 対象組織 ID（受信者解決キー・通知の {@code sourceId} 兼 {@code scopeId}）
 */
public record NotificationCreditFreeQuotaAlertEvent(Long organizationId) {
}
