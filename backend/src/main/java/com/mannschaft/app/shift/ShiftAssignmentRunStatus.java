package com.mannschaft.app.shift;

/**
 * シフト自動割当実行ログのステータス。
 */
public enum ShiftAssignmentRunStatus {

    /** 実行中 */
    RUNNING,

    /** 成功 */
    SUCCEEDED,

    /** 失敗 */
    FAILED,

    /** 目視確認済み（確定） */
    CONFIRMED,

    /** 取消済み */
    REVOKED
}
