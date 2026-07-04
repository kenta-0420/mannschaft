package com.mannschaft.app.dashboard.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 個人ダッシュボード: 全チーム・組織横断「要対応」集計レスポンス DTO。
 *
 * <p>ユーザーが所属する全チーム・全組織の未処理アイテム（回覧板/アンケート/出席確認）を
 * スコープ情報（scopeType/scopeSlug/scopeName）付きのフラットリストで返す。</p>
 *
 * <p>各アイテムには {@code scopeType}/{@code scopeSlug}/{@code scopeName}/{@code itemType}/
 * {@code itemId}/{@code title}/{@code deadline}/{@code startsAt} が含まれる（AC-15）。</p>
 *
 * <p>設計書: docs/features/F22.1_swipe_scope_dashboard / 個人横断「要対応」API 仕様</p>
 */
public record PersonalActionRequiredResponse(

        /** 全スコープの要対応アイテム一覧（フラットリスト）。 */
        @JsonProperty("items") List<ActionItem> items,

        /** アイテムの合計件数。 */
        @JsonProperty("total_count") int totalCount
) {

    /**
     * 個人横断「要対応」の 1 アイテム。
     *
     * <p>{@code itemType} は {@code "CIRCULATION"} / {@code "SURVEY"} / {@code "ATTENDANCE"} の 3 種別。
     * {@code scopeType} は {@code "TEAM"} / {@code "ORGANIZATION"}。</p>
     *
     * <p>{@code itemId} は circulation=UUID 文字列、survey/attendance=数値文字列。</p>
     */
    public record ActionItem(

            /** アイテム種別: CIRCULATION / SURVEY / ATTENDANCE */
            @JsonProperty("item_type") String itemType,

            /** スコープ種別: TEAM / ORGANIZATION */
            @JsonProperty("scope_type") String scopeType,

            /** スコープの数値 ID（URL 構築等のために保持）。 */
            @JsonProperty("scope_id") Long scopeId,

            /** スコープの slug（URL 構築用）。 */
            @JsonProperty("scope_slug") String scopeSlug,

            /** スコープの表示名。 */
            @JsonProperty("scope_name") String scopeName,

            /** アイテム ID（circulation=UUID 文字列、survey/attendance=数値文字列）。 */
            @JsonProperty("item_id") String itemId,

            /** タイトル。 */
            @JsonProperty("title") String title,

            /** 期限（nullable）。circulation の dueDate / survey の expiresAt。 */
            @JsonProperty("deadline") LocalDateTime deadline,

            /** 開始日時（attendance のみ設定、他は null）。 */
            @JsonProperty("starts_at") LocalDateTime startsAt
    ) {
    }
}
