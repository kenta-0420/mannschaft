package com.mannschaft.app.property;

/**
 * 物件履歴パッケージのステータス。
 * F09.13 設計書 §3 property_work_packages.status に対応。
 */
public enum WorkPackageStatus {

    /** 計画中 */
    PLANNED,

    /** 進行中 */
    IN_PROGRESS,

    /** 完了 */
    COMPLETED,

    /** クローズ */
    CLOSED,

    /** キャンセル */
    CANCELLED
}
