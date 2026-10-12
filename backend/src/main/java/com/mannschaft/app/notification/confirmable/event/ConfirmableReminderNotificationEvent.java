package com.mannschaft.app.notification.confirmable.event;

import com.mannschaft.app.notification.service.NotificationDeliveryRequest;

/**
 * 確認通知リマインド（受信者向け・送信者アラート）の通知配送要求イベント（CMP-260925-0905）。
 *
 * <p>{@code ConfirmableNotificationReminderRunner} が自身の独立トランザクション内で publish し、
 * {@code AFTER_COMMIT} でのみ {@link ConfirmableReminderNotificationListener} が配送する。
 * 送信済み記録がコミットされない限り通知は作られず、コミット前に WebSocket / Push が出ることもない。</p>
 *
 * @param request        通知配送要求
 * @param notificationId 確認通知ID（失敗ログ用）
 */
public record ConfirmableReminderNotificationEvent(NotificationDeliveryRequest request, Long notificationId) {
}
