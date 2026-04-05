package com.mannschaft.app.schedule;

/**
 * 出欠回答ステータス。
 */
public enum AttendanceStatus {
    /** 出席 */
    ATTENDING,
    /** 途中参加・途中退出 */
    PARTIAL,
    /** 欠席 */
    ABSENT,
    /** 未定 */
    UNDECIDED
}
