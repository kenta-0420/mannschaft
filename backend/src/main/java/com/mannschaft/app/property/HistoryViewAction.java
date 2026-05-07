package com.mannschaft.app.property;

/**
 * 物件履歴閲覧アクション種別。
 * F09.13 設計書 §3 property_work_history_views.action に対応。
 */
public enum HistoryViewAction {

    /** 閲覧 */
    VIEW,

    /** エクスポート */
    EXPORT,

    /** 添付ダウンロード */
    DOWNLOAD_ATTACHMENT
}
