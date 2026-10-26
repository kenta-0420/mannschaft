package com.mannschaft.app.committee.entity;

/**
 * 委員会招集状の解決状態。
 */
public enum CommitteeInvitationResolution {
    /** 受諾 */
    ACCEPTED,
    /** 辞退 */
    DECLINED,
    /** 期限切れ */
    EXPIRED,
    /** キャンセル */
    CANCELLED
}
