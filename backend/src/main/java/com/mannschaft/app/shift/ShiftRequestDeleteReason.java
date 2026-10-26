package com.mannschaft.app.shift;

/** シフト希望が論理削除された理由。 */
public enum ShiftRequestDeleteReason {
    WITHDRAWN,
    PARENT_DELETED,
    SLOT_DELETED
}
