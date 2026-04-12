package com.mannschaft.app.notification.confirmable.entity;

/**
 * 確認の経路（確認操作がどのチャネル経由で行われたか）。
 */
public enum ConfirmedVia {

    /** アプリ内UI操作 */
    APP,

    /** URLトークン経由（メール・LINE等） */
    TOKEN,

    /** 管理者による一括確認 */
    BULK
}
