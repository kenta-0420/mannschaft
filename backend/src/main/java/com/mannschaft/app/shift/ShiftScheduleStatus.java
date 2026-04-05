package com.mannschaft.app.shift;

/**
 * シフトスケジュールのステータス区分。
 */
public enum ShiftScheduleStatus {

    /** 下書き */
    DRAFT,

    /** 希望収集中 */
    COLLECTING,

    /** 調整中 */
    ADJUSTING,

    /** 公開済み */
    PUBLISHED,

    /** アーカイブ済み */
    ARCHIVED
}
