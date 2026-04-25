package com.mannschaft.app.shift;

/**
 * シフト変更依頼のステータス区分。
 */
public enum ChangeRequestStatus {

    /** 受付中 */
    OPEN,

    /** 承認済み */
    ACCEPTED,

    /** 却下済み */
    REJECTED,

    /** 依頼者による取下 */
    WITHDRAWN,

    /** 有効期限切れ */
    EXPIRED
}
