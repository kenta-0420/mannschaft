package com.mannschaft.app.recruitment;

public enum PenaltyLiftReason {
    /** 期限切れ自動解除 */
    AUTO_EXPIRED,
    /** 管理者手動解除 */
    ADMIN_MANUAL,
    /** 異議申立 REVOKED による解除 */
    DISPUTE_REVOKED
}
