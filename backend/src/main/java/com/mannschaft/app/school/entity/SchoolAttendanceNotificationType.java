package com.mannschaft.app.school.entity;

/** 学校出欠通知の種別（F03.12 連携時の識別子）。 */
public enum SchoolAttendanceNotificationType {
    /** 朝の点呼: 欠席 → 保護者へ確認通知 */
    ROLL_CALL_ABSENT,
    /** 朝の点呼: 出席 → 保護者へ到着通知（任意設定でOFF可） */
    ROLL_CALL_PRESENT,
    /** 時限別: 出席停止 → 担任・保護者へ通知 */
    PERIOD_ABSENT,
    /** 「前にいたのに今いない」移動検知アラート */
    TRANSITION_ALERT
}
