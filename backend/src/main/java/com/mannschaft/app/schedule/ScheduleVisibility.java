package com.mannschaft.app.schedule;

/**
 * スケジュールの公開範囲。
 */
public enum ScheduleVisibility {
    /** メンバーのみ */
    MEMBERS_ONLY,
    /** 組織全体 */
    ORGANIZATION,
    /** カスタム公開範囲テンプレート参照（F01.7） */
    CUSTOM_TEMPLATE
}
