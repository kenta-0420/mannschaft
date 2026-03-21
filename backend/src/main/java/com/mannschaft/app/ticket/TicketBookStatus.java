package com.mannschaft.app.ticket;

/**
 * 回数券のステータス。
 */
public enum TicketBookStatus {

    /** 決済待ち（Stripe Checkout 未完了） */
    PENDING,

    /** 利用可能 */
    ACTIVE,

    /** 期限切れ */
    EXPIRED,

    /** 使い切り（残数0） */
    EXHAUSTED,

    /** キャンセル・返金 */
    CANCELLED
}
