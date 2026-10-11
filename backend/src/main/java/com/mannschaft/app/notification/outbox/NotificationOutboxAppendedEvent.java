package com.mannschaft.app.notification.outbox;

/**
 * outbox に行を書いたことを relay に知らせる起こしのイベント（docs/architecture/notification_outbox.md §5）。
 *
 * <p>書き込み側は outbox の INSERT と同じ tx の中で publish する。relay は AFTER_COMMIT で受け、
 * 専用の executor（{@code notification-outbox-pool}）で即座に drain する。届かなくても予備のポーラーが拾う。</p>
 *
 * @param sourceName 書いた source の名前（{@link NotificationOutboxSource#name()}）
 */
public record NotificationOutboxAppendedEvent(String sourceName) {
}
