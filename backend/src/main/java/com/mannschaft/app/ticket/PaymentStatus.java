package com.mannschaft.app.ticket;

/**
 * 回数券決済のステータス。
 */
public enum PaymentStatus {

    /** 決済待ち */
    PENDING,

    /** 支払い完了 */
    PAID,

    /** 返金済み */
    REFUNDED,

    /** 部分返金 */
    PARTIALLY_REFUNDED,

    /** キャンセル */
    CANCELLED
}
