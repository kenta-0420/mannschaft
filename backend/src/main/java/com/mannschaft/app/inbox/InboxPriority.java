package com.mannschaft.app.inbox;

/**
 * F04.11 統合通知インボックス：自動緊急度。
 *
 * <p>各ソースの優先度を {@code InboxPriorityNormalizer} で単一値へ写像する（永続化しない・導出のみ）。
 * 設計書: docs/features/F04.11_notification_inbox/01_data_model.md §3.2。</p>
 */
public enum InboxPriority {

    /** 緊急 */
    URGENT,

    /** 重要 */
    HIGH,

    /** 通常 */
    NORMAL,

    /** 低 */
    LOW
}
