package com.mannschaft.app.dashboard.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.util.List;

/**
 * F10.1.1 / P1: 管理者向け横断「承認待ち」集約レスポンス DTO。
 *
 * <p>メンバー向け {@code ActionRequiredSummaryResponse}（「私が回答/確認すべきこと」）とは別物で、
 * こちらは「ADMIN/DEPUTY が承認/処理すべき承認タスク」をドメイン横断で集約する。
 * 集約対象ドメインは <b>スコープ別に動的</b>に決まる（設計書 03 §3.2）:</p>
 * <ul>
 *   <li>team スコープ: {@code RESERVATION} / {@code SHIFT_REQUEST} / {@code MATCHING}</li>
 *   <li>organization スコープ: {@code PAYMENT} のみ</li>
 * </ul>
 *
 * <p>無効なドメインは {@code domains[]} に含めない（{@code enabled:false} の空枠も出さない）。
 * JSON は snake_case 出力（プロジェクト REST 規約）。FE 型 {@code AdminActionRequiredSummary}
 * （camelCase・P3 で実装）との差異は命名変換規約に委ねる。</p>
 *
 * <p>設計書: docs/features/F10.1.1_team_org_admin_console/03_admin_action_required_api.md §3</p>
 */
@Builder
public record AdminActionRequiredResponse(

        /** スコープ種別（"TEAM" / "ORGANIZATION"）。 */
        @JsonProperty("scope_type") String scopeType,

        /** スコープ ID（内部 BIGINT）。 */
        @JsonProperty("scope_id") Long scopeId,

        /**
         * 当該スコープで有効な全ドメインの {@code pending_count} 合計。
         * {@code degraded=true} のドメインは不確定のため加算しない（0 件と集計失敗を区別・§4.3）。
         */
        @JsonProperty("total_pending") long totalPending,

        /** 当該スコープで有効なドメインのみ（§3.2）。無効ドメインは含めない。 */
        @JsonProperty("domains") List<DomainSection> domains
) {

    /**
     * ドメイン別の承認待ちセクション。
     */
    @Builder
    public record DomainSection(

            /** ドメイン enum（RESERVATION / SHIFT_REQUEST / MATCHING / PAYMENT）。 */
            @JsonProperty("domain") String domain,

            /** 承認待ち件数。{@code degraded=true} の場合は 0。 */
            @JsonProperty("pending_count") long pendingCount,

            /**
             * 一時障害（DB 接続断・タイムアウト）で集計できなかった場合のみ true。
             * 認可エラー・プログラミングエラーでは立たず、API 全体が当該ステータスを返す（§4.3）。
             */
            @JsonProperty("degraded") boolean degraded,

            /** FE が遷移する一覧ルート（BE がスラッグ解決済み）。 */
            @JsonProperty("list_route") String listRoute,

            /** preview_size 件までのプレビュー。 */
            @JsonProperty("items") List<PreviewItem> items
    ) {
    }

    /**
     * 承認待ちアイテムのプレビュー要素。
     */
    @Builder
    public record PreviewItem(

            /** 対象ドメインの主キーを文字列化（BIGINT/UUID を JSON 数値でなく文字列で統一）。 */
            @JsonProperty("id") String id,

            /** 表示用タイトル。 */
            @JsonProperty("title") String title,

            /** 申請者の表示名（バルク解決済み・N+1 回避）。 */
            @JsonProperty("requested_by") String requestedBy,

            /** 申請日時。 */
            @JsonProperty("requested_at") java.time.LocalDateTime requestedAt,

            /**
             * その 1 件の<b>個別遷移先</b>ルート（BE がスラッグ・主キーを解決済み）。
             * ドメインの一覧ルート（{@code list_route}・status 付き）とは別物で、id を含む
             * 個別画面へ飛ぶ（例 {@code /teams/{slug}/admin/reservations/{id}}・§3.1 / §3.3）。
             * シフトのように同一ドメイン内に複数種別がある場合は種別ごとに異なる個別遷移先を持つ
             * （変更依頼 {@code /shifts/change/{id}}・交代申請 {@code /shifts/swap/{id}}）。
             */
            @JsonProperty("detail_route") String detailRoute
    ) {
    }
}
