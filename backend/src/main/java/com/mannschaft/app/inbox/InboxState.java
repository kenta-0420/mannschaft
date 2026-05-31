package com.mannschaft.app.inbox;

/**
 * F04.11 統合通知インボックス：通知 1 件の状態。
 *
 * <p>オーバーレイ（{@code inbox_item_states}）とソース既読のマージ結果として導出する。
 * 設計書: docs/features/F04.11_notification_inbox/03_business_logic.md §4。</p>
 */
public enum InboxState {

    /** 未読（オーバーレイなし・ソース未読） */
    UNREAD,

    /** 既読（ソース既読・非アーカイブ・非スヌーズ） */
    READ,

    /** スヌーズ中（snoozed_until > now） */
    SNOOZED,

    /** アーカイブ済み（archived_at != null） */
    ARCHIVED
}
