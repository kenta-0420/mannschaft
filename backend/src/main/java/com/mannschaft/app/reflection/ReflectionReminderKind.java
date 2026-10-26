package com.mannschaft.app.reflection;

/**
 * 間隔反復リマインダーの種別（F06.5・§2.5）。
 *
 * <p>{@code reflection_spaced_reminders.kind} の CHECK 制約値と完全一致させること。</p>
 */
public enum ReflectionReminderKind {
    /** 想起間隔（1/3/7/14 日後）のリマインダー。entry_id 基準。 */
    SPACED,
    /** 定期考査前（14/7/3/1 日前）の総まとめリマインダー。theme_id 基準。 */
    PRE_EXAM
}
