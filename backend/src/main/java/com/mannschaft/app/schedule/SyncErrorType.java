package com.mannschaft.app.schedule;

/**
 * Google Calendar同期エラー種別。
 */
public enum SyncErrorType {

    AUTH_ERROR,
    QUOTA_EXCEEDED,
    NETWORK_ERROR,
    SERVER_ERROR
}
