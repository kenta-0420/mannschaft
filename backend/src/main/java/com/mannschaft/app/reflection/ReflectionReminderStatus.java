package com.mannschaft.app.reflection;

/**
 * 間隔反復リマインダーの送信ステータス（F06.5・§2.5 / §5.2）。
 *
 * <p>{@code reflection_spaced_reminders.status} の CHECK 制約値と完全一致させること。
 * PENDING→SENT 遷移で二重送信を防止する（AC-10）。</p>
 */
public enum ReflectionReminderStatus {
    /** 未送信（バッチ走査対象）。 */
    PENDING,
    /** 送信済み。 */
    SENT,
    /** キャンセル済み（親削除・再生成・過去日ガード等）。 */
    CANCELLED
}
