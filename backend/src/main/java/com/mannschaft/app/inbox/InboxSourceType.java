package com.mannschaft.app.inbox;

/**
 * F04.11 統合通知インボックス：通知ソース種別（=自動「種類」）。
 *
 * <p>5 ソースを単一の表示モデル {@code InboxItem} に正規化する際の出自を表す。
 * {@code (sourceType, sourceId)} の複合論理キーで通知 1 件を参照する。
 * 設計書: docs/features/F04.11_notification_inbox/01_data_model.md §3。</p>
 */
public enum InboxSourceType {

    /** F04.3 通知（notifications） */
    NOTIFICATION,

    /** social.announcement / F02.8 お知らせ（announcement_feeds） */
    ANNOUNCEMENT,

    /** F04.1 メンション（mentions） */
    MENTION,

    /** F04.9 確認必須通知（confirmable_notification_recipients） */
    CONFIRMABLE,

    /** F02.3 TODO 期限（todos） */
    TODO_DUE
}
