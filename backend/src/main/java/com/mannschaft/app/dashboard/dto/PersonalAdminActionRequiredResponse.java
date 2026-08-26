package com.mannschaft.app.dashboard.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 個人ダッシュボード: 全チーム・組織横断「承認待ち」集計レスポンス DTO（司令塔第二弾）。
 *
 * <p>複数チーム/組織を管理するユーザー（ADMIN/DEPUTY_ADMIN）が、自身が管理する全スコープの
 * 承認待ちアイテム（予約承認/シフトリクエスト/マッチング応募/未収請求）をスコープ情報付きの
 * フラットリストで横断的に確認できるようにする集計 API のレスポンス。</p>
 *
 * <p>{@link PersonalActionRequiredResponse}（「私が回答/確認すべきこと」・MEMBER 含む全メンバー向け）
 * とは<b>別物</b>。こちらは「ADMIN/DEPUTY が承認/処理すべき承認タスク」のみを、ユーザーが
 * ADMIN/DEPUTY_ADMIN として管理するスコープに限定して集約する（AC-B1-1）。</p>
 *
 * <p>{@code totalPending} は各スコープの {@code AdminActionRequiredResponse.totalPending}
 * （縮退ドメインを除く実件数）の合計であり、{@code items} のプレビュー件数上限とは独立する
 * （プレビューで切り詰められても件数バッジは正確な値を返す）。</p>
 *
 * <p>設計書: ADHD-UX戦役第四陣第二弾「承認待ち横断集約」</p>
 */
public record PersonalAdminActionRequiredResponse(

        /** 全管理スコープの承認待ちアイテム一覧（フラットリスト・スコープごとのプレビュー件数まで）。 */
        @JsonProperty("items") List<ActionItem> items,

        /** 縮退ドメインを除く承認待ちの実合計件数。 */
        @JsonProperty("total_pending") long totalPending
) {

    /**
     * 個人横断「承認待ち」の 1 アイテム。
     *
     * <p>{@code domain} は {@code "RESERVATION"} / {@code "SHIFT_REQUEST"} / {@code "MATCHING"}
     * （team スコープ）/ {@code "PAYMENT"}（organization スコープ）のいずれか。
     * {@code scopeType} は {@code "TEAM"} / {@code "ORGANIZATION"}。</p>
     *
     * <p>{@code @Schema(name=)} は必須。{@link PersonalActionRequiredResponse.ActionItem} と
     * 単純名が同一のため、無指定だと OpenAPI 生成で後勝ちの schema 上書きが起きる
     * （既知の同名 nested schema 破壊・memory {@code feedback_openapi_nested_schema_name_collision}）。</p>
     */
    @Schema(name = "PersonalAdminActionRequiredItem")
    public record ActionItem(

            /** ドメイン種別: RESERVATION / SHIFT_REQUEST / MATCHING / PAYMENT */
            @JsonProperty("domain") String domain,

            /** スコープ種別: TEAM / ORGANIZATION */
            @JsonProperty("scope_type") String scopeType,

            /** スコープの数値 ID（URL 構築等のために保持）。 */
            @JsonProperty("scope_id") Long scopeId,

            /** スコープの slug（URL 構築用）。 */
            @JsonProperty("scope_slug") String scopeSlug,

            /** スコープの表示名。 */
            @JsonProperty("scope_name") String scopeName,

            /** 対象ドメインの主キーを文字列化したもの。 */
            @JsonProperty("item_id") String itemId,

            /** 表示用タイトル。 */
            @JsonProperty("title") String title,

            /** 申請者の表示名（バルク解決済み・N+1 回避）。 */
            @JsonProperty("requested_by") String requestedBy,

            /** 申請日時。 */
            @JsonProperty("requested_at") LocalDateTime requestedAt,

            /** その 1 件の個別遷移先ルート（BE がスラッグ・主キーを解決済み）。 */
            @JsonProperty("detail_route") String detailRoute
    ) {
    }
}
