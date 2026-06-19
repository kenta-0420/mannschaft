package com.mannschaft.app.dashboard.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.math.BigDecimal;

/**
 * F10.1.1 / P3b Wave3: 管理者レンズ「予算サマリ」の DTO
 * （{@code ADMIN_TEAM_BUDGET} / {@code ADMIN_ORG_BUDGET}・設計書 02）。
 *
 * <p>当該スコープの<b>現年度</b>の「配分 / 実績 / 残 / 超過カテゴリ数」を返す。
 * 現年度が無い場合は {@code has_current_fiscal_year=false}・各数値 0・{@code fiscal_year_name=null}
 * を返し、症状を隠さず「当年度未設定」を正直に伝える（ウィジェットは導線のみ表示）。</p>
 *
 * <ul>
 *   <li>配分 = {@code budget_allocations.amount} の合計。</li>
 *   <li>実績 = 承認済み EXPENSE 取引の合計。</li>
 *   <li>残 = 配分 − 実績（収入−支出の balance は流用しない）。</li>
 *   <li>超過カテゴリ数 = カテゴリ毎の (配分 − 実績) が負になるカテゴリの数。</li>
 * </ul>
 *
 * <p>JSON は snake_case（プロジェクト REST 規約・FE は camelCase へ変換）。
 * 金額は budget ドメインの内部表現（{@code BigDecimal}）をそのまま露出する。</p>
 *
 * <p>設計書: docs/features/F10.1.1_team_org_admin_console/02_admin_lens_widgets.md</p>
 */
@Builder
public record AdminBudgetSummaryResponse(

        /** 現年度が存在するか。false のとき他の数値は 0・fiscal_year_name は null。 */
        @JsonProperty("has_current_fiscal_year") boolean hasCurrentFiscalYear,

        /** 現年度名（未設定時 null）。 */
        @JsonProperty("fiscal_year_name") String fiscalYearName,

        /** 配分合計（budget_allocations.amount の合計）。 */
        @JsonProperty("allocation") BigDecimal allocation,

        /** 実績合計（承認済み EXPENSE 取引の合計）。 */
        @JsonProperty("actual") BigDecimal actual,

        /** 残（配分 − 実績）。負になり得る（超過時）。 */
        @JsonProperty("remaining") BigDecimal remaining,

        /** 超過カテゴリ数（カテゴリ毎の残が負のもの・閾値非依存）。 */
        @JsonProperty("over_budget_category_count") long overBudgetCategoryCount
) {
}
