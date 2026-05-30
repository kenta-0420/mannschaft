package com.mannschaft.app.inbox.dto;

import java.util.Map;

/**
 * F04.11 統合通知インボックス：件数サマリレスポンス DTO（タブ/バッジ用）。
 *
 * <p>状態別・緊急度別・種類別の件数。設計書: 02_api_design.md §3.2。</p>
 *
 * @param byState      状態別件数（INBOX/SNOOZED/ARCHIVED 等）
 * @param byPriority   緊急度別件数（URGENT/HIGH/NORMAL/LOW）
 * @param bySourceType 種類別件数（NOTIFICATION/ANNOUNCEMENT/MENTION/CONFIRMABLE/TODO_DUE）
 */
public record InboxSummaryResponse(
        Map<String, Long> byState,
        Map<String, Long> byPriority,
        Map<String, Long> bySourceType
) {
}
