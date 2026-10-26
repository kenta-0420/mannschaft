package com.mannschaft.app.inbox;

/**
 * F04.11 統合通知インボックスが {@code notifications} テーブルへ発行する専用通知種別の定数。
 *
 * <p>インボックス由来の通知（例: スヌーズ復帰 push）を、NOTIFICATION アダプタが受信箱へ
 * 再流入させない（自己増殖を防ぐ）ための除外キーとして用いる。設計書:
 * docs/features/F04.11_notification_inbox/03_business_logic.md §5。</p>
 */
public final class InboxNotificationTypes {

    /**
     * スヌーズ復帰 push の通知種別。
     *
     * <p>{@code InboxSnoozeRevivalBatchService} が {@code NotificationHelper.notify} 経由で
     * この種別の通知を発行し、{@code NotificationInboxAdapter} はこの種別をインボックス集約から
     * 除外する（ベル/通知一覧には出るが受信箱の新規カードにはしない）。</p>
     */
    public static final String INBOX_SNOOZE_REVIVAL = "INBOX_SNOOZE_REVIVAL";

    private InboxNotificationTypes() {
        // インスタンス化禁止（定数ホルダー）
    }
}
