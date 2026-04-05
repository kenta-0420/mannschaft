package com.mannschaft.app.admin;

/**
 * 通知配信チャネルの種別。
 */
public enum NotificationChannel {
    /** プッシュ通知 */
    PUSH,
    /** メール */
    EMAIL,
    /** LINE */
    LINE,
    /** アプリ内通知 */
    IN_APP
}
