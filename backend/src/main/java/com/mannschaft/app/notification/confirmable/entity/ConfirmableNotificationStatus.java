package com.mannschaft.app.notification.confirmable.entity;

/**
 * 確認通知のステータス。
 */
public enum ConfirmableNotificationStatus {

    /** 受付中（受信者が確認可能な状態） */
    ACTIVE,

    /** 完了（全員または管理者による完了操作） */
    COMPLETED,

    /** 期限切れ（deadline_at を過ぎて未完了のまま） */
    EXPIRED,

    /** キャンセル（送信者が取り消し） */
    CANCELLED
}
