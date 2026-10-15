package com.mannschaft.app.moderation;

/**
 * ヤバいやつ解除申請ステータス。
 */
public enum UnflagRequestStatus {
    /** 未対応 */
    PENDING,
    /** 承認 */
    ACCEPTED,
    /** 却下 */
    REJECTED
}
