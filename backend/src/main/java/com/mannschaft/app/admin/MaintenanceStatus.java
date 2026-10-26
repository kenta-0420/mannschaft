package com.mannschaft.app.admin;

/**
 * メンテナンススケジュールのステータス。
 */
public enum MaintenanceStatus {
    /** 予定 */
    SCHEDULED,
    /** 実施中 */
    ACTIVE,
    /** 完了 */
    COMPLETED,
    /** キャンセル */
    CANCELLED
}
