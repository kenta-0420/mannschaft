package com.mannschaft.app.notification.credit.entity;

/**
 * クレジット購入の決済ステータス。
 */
public enum NotificationCreditPurchaseStatus {

    /** 決済セッション作成済み、未決済 */
    PENDING,

    /** 決済完了 */
    PAID,

    /** キャンセル（セッション期限切れ等） */
    CANCELLED,

    /** 返金済み */
    REFUNDED
}
