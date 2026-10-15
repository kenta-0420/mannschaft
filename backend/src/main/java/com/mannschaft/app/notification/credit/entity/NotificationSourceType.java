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

    /**
     * {@code ConfirmableFanoutChunkSink}（手動送信 {@code ConfirmableNotificationService#sendAsync} の
     * fan-out チャンク確定）経由の確認通知。同期 {@code send} / {@code sendFromSource} は自動・システム
     * 通知専用でカウント対象外のため、この種別では消費しない（CMP-260930-1932）。
     */
    CONFIRMABLE
}
