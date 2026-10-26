package com.mannschaft.app.schedule;

/**
 * 年間行事コピー時の日付シフト方式。
 */
public enum DateShiftMode {
    /** 正確な日数差でシフト */
    EXACT_DAYS,
    /** 同一曜日に合わせてシフト */
    SAME_WEEKDAY
}
