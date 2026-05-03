package com.mannschaft.app.todo;

/**
 * TODOのステータス。
 */
public enum TodoStatus {
    /** 未着手 */
    OPEN,
    /** 進行中 */
    IN_PROGRESS,
    /** 完了 */
    COMPLETED,
    /** キャンセル（F03.5 Phase 4-γ: シフト ARCHIVED 時に自動遷移） */
    CANCELLED
}
