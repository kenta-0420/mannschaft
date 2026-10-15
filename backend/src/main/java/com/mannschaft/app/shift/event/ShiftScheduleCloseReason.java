package com.mannschaft.app.shift.event;

/**
 * {@link ShiftScheduleClosedEvent} の発生経路（CMP-260909-1445）。
 */
public enum ShiftScheduleCloseReason {

    /** {@code deleteSchedule} による論理削除。 */
    DELETED,

    /** PUBLISHED から COLLECTING / ADJUSTING への後戻り遷移（公開取消）。 */
    UNPUBLISHED
}
