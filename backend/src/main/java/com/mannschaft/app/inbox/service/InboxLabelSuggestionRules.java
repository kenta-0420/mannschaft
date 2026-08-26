package com.mannschaft.app.inbox.service;

import com.mannschaft.app.inbox.InboxLabelSuggestion;
import com.mannschaft.app.inbox.InboxPriority;
import com.mannschaft.app.inbox.InboxSourceType;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * F04.11 統合通知インボックス：自動ラベリング提案の静的ルール（案C・非永続・導出のみ）。
 *
 * <p>{@code (InboxSourceType, InboxPriority)} から提案キー（{@link InboxLabelSuggestion}）を導出する純関数。
 * DB には一切触れない・状態を持たない。設計書: 03_business_logic.md §10（提案ルール表）。</p>
 *
 * <p><b>提案過多を避ける（ADHD 要件）</b>: 1 アイテムあたり提案は最大 1 個に絞る。
 * 該当しない組み合わせは空リストを返す（＝提案なし）。FE はこれを「提案チップ」として描画し、
 * 1 タップで {@code suggest-apply} を呼ぶ。</p>
 *
 * <table>
 *   <caption>提案ルール表（最終形）</caption>
 *   <tr><th>sourceType</th><th>priority 条件</th><th>提案キー</th></tr>
 *   <tr><td>MENTION</td><td>不問</td><td>REPLY_NEEDED</td></tr>
 *   <tr><td>CONFIRMABLE</td><td>URGENT / HIGH</td><td>ACTION_NEEDED</td></tr>
 *   <tr><td>TODO_DUE</td><td>URGENT（期限切れ）</td><td>URGENT</td></tr>
 *   <tr><td>ANNOUNCEMENT</td><td>不問</td><td>READ_LATER</td></tr>
 *   <tr><td>NOTIFICATION</td><td>URGENT</td><td>ACTION_NEEDED</td></tr>
 *   <tr><td>上記以外</td><td>—</td><td>（提案なし）</td></tr>
 * </table>
 */
@Component
public class InboxLabelSuggestionRules {

    /**
     * sourceType・priority から提案キーを導出する（最大 1 件）。該当なしは空リスト。
     *
     * @param sourceType 通知ソース種別（null 不可）
     * @param priority   自動緊急度（null 不可）
     * @return 提案キー（0 または 1 件）
     */
    public List<InboxLabelSuggestion> suggest(InboxSourceType sourceType, InboxPriority priority) {
        if (sourceType == null || priority == null) {
            return List.of();
        }
        return switch (sourceType) {
            case MENTION -> List.of(InboxLabelSuggestion.REPLY_NEEDED);
            case ANNOUNCEMENT -> List.of(InboxLabelSuggestion.READ_LATER);
            case CONFIRMABLE ->
                    isUrgentOrHigh(priority)
                            ? List.of(InboxLabelSuggestion.ACTION_NEEDED)
                            : List.of();
            case TODO_DUE ->
                    priority == InboxPriority.URGENT
                            ? List.of(InboxLabelSuggestion.URGENT)
                            : List.of();
            case NOTIFICATION ->
                    priority == InboxPriority.URGENT
                            ? List.of(InboxLabelSuggestion.ACTION_NEEDED)
                            : List.of();
        };
    }

    private boolean isUrgentOrHigh(InboxPriority priority) {
        return priority == InboxPriority.URGENT || priority == InboxPriority.HIGH;
    }
}
