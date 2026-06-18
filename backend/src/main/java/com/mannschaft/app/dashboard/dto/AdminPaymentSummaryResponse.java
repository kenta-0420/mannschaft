package com.mannschaft.app.dashboard.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

/**
 * F10.1.1 / P3b: 組織パネル管理者レンズ ⑤ {@code ADMIN_ORG_PAYMENTS} のサマリ DTO。
 *
 * <p>組織が発行した請求のうち「未収」を 2 区分（未収件数／期限超過件数）で返す。
 * 既存の P1 横断「承認待ち」集約（{@link AdminActionRequiredResponse} の PAYMENT ドメイン）は
 * 未収 3 ステータス（SENT/VIEWED/OVERDUE）を 1 件にまとめて {@code total_pending} に積むのに対し、
 * 本サマリは {@code overdue_count}（OVERDUE 単体・期限超過）を分離して表示する（設計書 02 §2.3 ③）。</p>
 *
 * <p>JSON は snake_case（プロジェクト REST 規約・FE は camelCase へ変換）。</p>
 *
 * <p>設計書: docs/features/F10.1.1_team_org_admin_console/02_admin_lens_widgets.md §2.3 ③</p>
 */
@Builder
public record AdminPaymentSummaryResponse(

        /** 未収件数（SENT/VIEWED/OVERDUE すべて＝まだ支払い完了していない請求の総数）。 */
        @JsonProperty("unsettled_count") long unsettledCount,

        /** 期限超過件数（OVERDUE 単体＝支払期限を過ぎた未収請求）。{@code unsettled_count} の内数。 */
        @JsonProperty("overdue_count") long overdueCount
) {
}
