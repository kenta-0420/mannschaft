package com.mannschaft.app.notification.credit.entity;

/**
 * 通知発生源の種別。課金カウントに用いる。
 *
 * <p>告知通知のみカウント対象とし、自動イベント通知・システム通知・1:1DMは対象外。</p>
 */
public enum NotificationSourceType {

    /** {@link com.mannschaft.app.notification.service.NotificationHelper#notifyAll} (isBillable=true) 経由の一斉送信 */
    NOTIFY_ALL,

    /** {@link com.mannschaft.app.directmail.service.DirectMailService#sendMail} 経由のSESメール送信 */
    DIRECT_MAIL,

    /** {@link com.mannschaft.app.notification.confirmable.service.ConfirmableNotificationService#send} 経由の確認通知 */
    CONFIRMABLE
}
