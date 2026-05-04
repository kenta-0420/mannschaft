package com.mannschaft.app.shiftbudget.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * F08.7 シフト予算割当 作成リクエスト DTO。
 *
 * <p>設計書 F08.7 (v1.2) §6.2.1 に準拠。</p>
 *
 * <p>マスター御裁可 Q1（案A）: {@code project_id} は Phase 9-β では受け付けず常に NULL。
 * Phase 9-γ で API 拡張予定。</p>
 *
 * @param teamId           チームID（NULL = 組織全体スコープ）
 * @param fiscalYearId     会計年度ID
 * @param budgetCategoryId 費目ID（人件費配下）
 * @param periodStart      適用開始日（通常月初）
 * @param periodEnd        適用終了日（通常月末）
 * @param allocatedAmount  割当額（円, 0 以上）
 * @param currency         通貨コード（NULL 時は JPY）
 * @param note             備考
 */
public record AllocationCreateRequest(

        @JsonProperty("team_id")
        Long teamId,

        @NotNull
        @JsonProperty("fiscal_year_id")
        Long fiscalYearId,

        @NotNull
        @JsonProperty("budget_category_id")
        Long budgetCategoryId,

        @NotNull
        @JsonProperty("period_start")
        LocalDate periodStart,

        @NotNull
        @JsonProperty("period_end")
        LocalDate periodEnd,

        @NotNull
        @PositiveOrZero
        @JsonProperty("allocated_amount")
        BigDecimal allocatedAmount,

        @Size(min = 3, max = 3)
        @JsonProperty("currency")
        String currency,

        @Size(max = 500)
        @JsonProperty("note")
        String note
) {
}
