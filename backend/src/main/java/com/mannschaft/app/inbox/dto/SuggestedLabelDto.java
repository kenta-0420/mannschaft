package com.mannschaft.app.inbox.dto;

import com.mannschaft.app.inbox.InboxLabelSuggestion;

import java.util.UUID;

/**
 * F04.11 統合通知インボックス：自動ラベリング「提案」DTO（案C・読み取り時導出・非永続）。
 *
 * <p>{@link com.mannschaft.app.inbox.service.InboxAggregationService} が各アイテムへ静的ルール
 * （{@link com.mannschaft.app.inbox.service.InboxLabelSuggestionRules}）を適用して算出する。
 * DB には保存しない。FE は {@code suggestionKey} を i18n で表示名解決し、提案チップを描画する。
 * チップ 1 タップで {@code POST /api/v1/inbox/labels/suggest-apply} を呼び実ラベルを find-or-create して付与する。</p>
 *
 * @param suggestionKey   提案キー（enum・UI 表示名は FE が i18n 解決）
 * @param color           既定色 #RRGGBB（提案キーの既定値。ユーザーは付与後に変更可）
 * @param existingLabelId ユーザーが既に持つ同義ラベルの ID（名寄せできれば設定・できなければ null）。
 *                        null の場合 FE は suggest-apply の find-or-create に倒す。
 */
public record SuggestedLabelDto(
        InboxLabelSuggestion suggestionKey,
        String color,
        UUID existingLabelId
) {
}
