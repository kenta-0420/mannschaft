package com.mannschaft.app.shift;

/**
 * シフト交代リクエストのステータス区分。
 */
public enum SwapRequestStatus {

    /** 申請中 */
    PENDING,

    /** 相手が承諾 */
    ACCEPTED,

    /** 管理者が承認 */
    APPROVED,

    /** 却下 */
    REJECTED,

    /** キャンセル */
    CANCELLED
}
