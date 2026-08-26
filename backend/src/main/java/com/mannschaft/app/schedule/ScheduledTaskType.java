package com.mannschaft.app.schedule;

/**
 * 予約タスク種別（機能55）。
 *
 * <p>{@code schedule_scheduled_tasks.task_type} にマップ。materialize 時に生成する実体を決定する。</p>
 */
public enum ScheduledTaskType {

    /** 出欠アンケート（EventSurvey）を生成する。 */
    SURVEY,

    /** 出欠確認（ScheduleAttendance）を生成する。 */
    ATTENDANCE
}
