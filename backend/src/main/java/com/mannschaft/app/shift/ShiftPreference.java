package com.mannschaft.app.shift;

/**
 * シフト希望の優先度区分。
 */
public enum ShiftPreference {

    /** 希望（出勤したい） */
    PREFERRED,

    /** 可能（出勤可能） */
    AVAILABLE,

    /** 不可（出勤不可） */
    UNAVAILABLE
}
