package com.mannschaft.app.quickmemo.event;

/**
 * ポイっとメモ リマインド通知の配送要求イベント（Issue #2834 / CMP-056 第2群ロット1）。
 *
 * <p>{@code QuickMemoReminderRunner#markRemindersSent} が 1 ユーザーぶんの送信済み記録を
 * 独立トランザクションでコミットする直前に publish し、
 * {@link QuickMemoReminderNotificationListener} が {@code AFTER_COMMIT} で受け取る。</p>
 *
 * <p>プライバシー保護（F02.5 H2 対応）のため、メモのタイトル・内容は<b>イベントにも載せない</b>。
 * 通知文言に使うのは件数だけである。</p>
 *
 * @param recipientUserId 受信者ユーザーID（リマインド対象メモの所有者）
 * @param memoCount       このリマインドで対象となったメモの件数（通知本文に埋める）
 */
public record QuickMemoReminderNotificationEvent(
        Long recipientUserId,
        int memoCount) {
}
