package com.mannschaft.app.gamification;

/**
 * バッジ獲得条件種別。
 */
public enum BadgeConditionType {

    /** 出席率 */
    ATTENDANCE_RATE,

    /** 月間ランキング */
    MONTHLY_RANK,

    /** 累計カウント */
    CUMULATIVE_COUNT,

    /** 連続日数 */
    CONSECUTIVE_DAYS,

    /** 手動付与 */
    MANUAL
}
