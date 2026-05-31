package com.mannschaft.app.inbox.dto;

import com.mannschaft.app.inbox.InboxPriority;
import com.mannschaft.app.inbox.InboxSourceType;
import com.mannschaft.app.inbox.InboxState;

import java.time.LocalDateTime;
import java.util.List;

/**
 * F04.11 統合通知インボックス：統一表示 DTO（5 ソースを正規化した API レスポンス）。
 *
 * <p>永続化しない・導出のみ。設計書: 01_data_model.md §3.1 / 02_api_design.md §3.1。</p>
 *
 * @param id           複合論理ID = {@code "{sourceType}:{sourceId}"}（例 {@code NOTIFICATION:123}）
 * @param sourceType   通知ソース種別（=自動「種類」）
 * @param sourceId     各ソース PK
 * @param title        タイトル
 * @param excerpt      抜粋（サニタイズ済み・150 字目安）
 * @param priority     自動緊急度
 * @param scope        スコープ（type/id/name）
 * @param actionUrl    遷移先 URL
 * @param occurredAt   発生時刻（TODO は due_date 基準）
 * @param state        状態（オーバーレイ＋ソース既読のマージ結果）
 * @param snoozedUntil スヌーズ解除予定（null 可）
 * @param labels       付与ラベル一覧
 */
public record InboxItemDto(
        String id,
        InboxSourceType sourceType,
        Long sourceId,
        String title,
        String excerpt,
        InboxPriority priority,
        ScopeDto scope,
        String actionUrl,
        LocalDateTime occurredAt,
        InboxState state,
        LocalDateTime snoozedUntil,
        List<LabelDto> labels
) {

    /**
     * 通知が属するスコープ（チーム/組織/個人など）。
     *
     * @param type スコープ種別（例 ORGANIZATION / TEAM / PERSONAL）
     * @param id   スコープID（null 可＝個人）
     * @param name スコープ名称（解決済み・null 可）
     */
    public record ScopeDto(
            String type,
            Long id,
            String name
    ) {
    }
}
