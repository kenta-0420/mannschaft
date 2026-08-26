package com.mannschaft.app.schedule;

/**
 * 予約タスクの状態（機能55）。
 *
 * <p>{@code schedule_scheduled_tasks.status} にマップ。
 * PENDING → CREATED（materialize 成功）/ CANCELLED（取消）/ FAILED（試行打ち止め）。</p>
 */
public enum ScheduledTaskStatus {

    /** materialize 待ち（初期状態）。 */
    PENDING,

    /** materialize 済み（実体生成完了）。 */
    CREATED,

    /** 取消済み（materialize しない）。 */
    CANCELLED,

    /** materialize 失敗（試行打ち止め）。 */
    FAILED
}
