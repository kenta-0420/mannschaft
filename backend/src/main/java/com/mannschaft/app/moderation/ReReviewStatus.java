package com.mannschaft.app.moderation;

/**
 * WARNING再レビューステータス。
 */
public enum ReReviewStatus {
    /** 未対応 */
    PENDING,
    /** 取消（ADMINが覆す） */
    OVERTURNED,
    /** 維持 */
    UPHELD,
    /** SYSTEM_ADMINに昇格 */
    ESCALATED,
    /** SYSTEM_ADMIN承認 */
    APPEAL_ACCEPTED,
    /** SYSTEM_ADMIN却下 */
    APPEAL_REJECTED
}
