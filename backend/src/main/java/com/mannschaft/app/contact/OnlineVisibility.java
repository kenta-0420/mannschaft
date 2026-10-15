package com.mannschaft.app.contact;

/**
 * オンライン状態の公開範囲。
 */
public enum OnlineVisibility {
    /** 誰にも見せない（デフォルト） */
    NOBODY,
    /** 連絡先ユーザーにのみ表示 */
    CONTACTS_ONLY,
    /** 全員に表示 */
    EVERYONE
}
