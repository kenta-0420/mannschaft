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
 * @param canonicalRef 名寄せ用の正規化済み終端実体キー（Phase 3 ①）。
 *                     正規化成功時は {@code "BLOG_POST:123"} のような {@code "{ReferenceType}:{terminalId}"}、
 *                     正規化不能時は自分自身 {@code "{sourceType}:{sourceId}"}（＝誤って畳まれない・設計書 §8）。
 * @param groupCount   名寄せで畳まれた構成メンバー件数（単一は 1）。FE の「N 件」バッジ用。
 * @param groupMembers 畳まれた全構成メンバーの参照（単一は自分自身 1 件）。FE が Phase 2 bulk triage で
 *                     各メンバーへ一括適用するための公開データ（「片方だけ既読/アーカイブ」防止・設計書 §8）。
 * @param suggestedLabels 自動ラベリング提案（案C・Phase 4・<b>非永続/読み取り時導出</b>）。
 *                        {@code InboxAggregationService} が静的ルールで算出する。FE は提案チップを描画し
 *                        1 タップで {@code suggest-apply} を呼ぶ。提案がなければ空リスト（設計書 03 §10）。
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
        List<LabelDto> labels,
        String canonicalRef,
        int groupCount,
        List<InboxItemRef> groupMembers,
        List<SuggestedLabelDto> suggestedLabels
) {

    /**
     * 提案ラベルを省いた従来 15 引数の互換コンストラクタ（{@code suggestedLabels} は空リスト）。
     *
     * <p>各ソースアダプタ・triage サービスは提案を計算しないためこの形を使う。提案は集約サービスが
     * 読み取り時に {@link #withSuggestedLabels(List)} で被せる。</p>
     */
    public InboxItemDto(
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
            List<LabelDto> labels,
            String canonicalRef,
            int groupCount,
            List<InboxItemRef> groupMembers) {
        this(id, sourceType, sourceId, title, excerpt, priority, scope, actionUrl, occurredAt,
                state, snoozedUntil, labels, canonicalRef, groupCount, groupMembers, List.of());
    }

    /**
     * 提案ラベルだけを差し替えた新インスタンスを返す（イミュータブル・他フィールドは保持）。
     */
    public InboxItemDto withSuggestedLabels(List<SuggestedLabelDto> suggested) {
        return new InboxItemDto(id, sourceType, sourceId, title, excerpt, priority, scope, actionUrl,
                occurredAt, state, snoozedUntil, labels, canonicalRef, groupCount, groupMembers,
                suggested == null ? List.of() : suggested);
    }

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
