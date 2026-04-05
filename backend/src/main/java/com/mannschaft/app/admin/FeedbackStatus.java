package com.mannschaft.app.admin;

/**
 * フィードバック投稿のステータス。
 */
public enum FeedbackStatus {
    /** 未対応 */
    OPEN,
    /** 回答済み */
    RESPONDED,
    /** 対応中 */
    IN_PROGRESS,
    /** クローズ */
    CLOSED
}
