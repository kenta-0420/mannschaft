package com.mannschaft.app.notification.outbox;

/**
 * outbox の1行が通知ドメインに頼む取り込みの種類（docs/architecture/notification_outbox.md §4）。
 */
public enum NotificationOutboxMessageKind {
    /** fan-out ジョブ1件（＋文面6行）を冪等に登録する。P1 で使う。 */
    FANOUT,
    /** 宛先集合（見出し＋宛先チーム）と fan-out ジョブを1つの tx で冪等に登録する。6-E'（social）で使う。 */
    FANOUT_WITH_AUDIENCE
}
