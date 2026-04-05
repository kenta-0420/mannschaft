package com.mannschaft.app.digest;

/**
 * ダイジェスト自動生成のスケジュール種別。
 */
public enum ScheduleType {
    /** 手動のみ */
    MANUAL,
    /** 毎日 */
    DAILY,
    /** 毎週指定曜日 */
    WEEKLY,
    /** 毎月1日 */
    MONTHLY
}
