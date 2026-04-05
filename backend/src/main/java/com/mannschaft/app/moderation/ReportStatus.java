package com.mannschaft.app.moderation;

/**
 * 通報のステータス。
 */
public enum ReportStatus {
    /** 未対応 */
    PENDING,
    /** 対応中 */
    REVIEWING,
    /** 承認（対応済み） */
    RESOLVED,
    /** エスカレーション中 */
    ESCALATED,
    /** 却下 */
    DISMISSED
}
