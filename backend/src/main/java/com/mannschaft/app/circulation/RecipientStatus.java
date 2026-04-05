package com.mannschaft.app.circulation;

/**
 * 回覧受信者ステータス。受信者ごとの確認状態を表す。
 */
public enum RecipientStatus {
    PENDING,
    STAMPED,
    SKIPPED,
    REJECTED
}
