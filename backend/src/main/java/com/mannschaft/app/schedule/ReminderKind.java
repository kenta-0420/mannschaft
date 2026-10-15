package com.mannschaft.app.schedule;

/**
 * リマインダーの指定方式（機能55）。
 *
 * <p>出欠リマインダー / 個人スケジュールリマインダー双方の {@code reminder_kind} にマップ。</p>
 */
public enum ReminderKind {

    /** 相対指定：親予定の開始時刻 N 分前。{@code remindBeforeMinutes} を使用。 */
    RELATIVE,

    /** 絶対指定：固定日時。{@code remindAt} を使用。 */
    ABSOLUTE
}
