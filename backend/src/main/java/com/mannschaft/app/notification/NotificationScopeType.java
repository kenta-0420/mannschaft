package com.mannschaft.app.notification;

/**
 * 通知のスコープ種別。通知の発生元スコープを表す。
 */
public enum NotificationScopeType {
    TEAM,
    ORGANIZATION,
    PERSONAL,
    SYSTEM,
    /** フレンドチーム経由の通知（管理者フィード）。scope_id = 受信チームID */
    FRIEND_TEAM,
    /** フォルダ単位の通知受信設定。scope_id = folder_id */
    FRIEND_FOLDER,
    /** 委員会スコープ。scope_id = committee_id */
    COMMITTEE
}
