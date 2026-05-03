package com.mannschaft.app.shiftbudget.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;

/**
 * シフト予算逆算 API のレスポンス DTO。
 *
 * <p>設計書 F08.7 §6.2.2 形状に準拠。{@code calculation} 文字列も含めて完全一致させる。</p>
 *
 * @param budgetAmount      入力された予算額
 * @param avgHourlyRate     算出（または指定）された平均時給
 * @param slotHours         スロット時間
 * @param requiredSlots     必要枠数 ({@code floor(budget / (rate × hours))})
 * @param calculation       計算式の人間可読表記
 * @param warnings          境界ケースの警告コードリスト
 * @param positionBreakdown POSITION_AVG モード時のみ含むポジション別内訳
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RequiredSlotsResponse(

        @JsonProperty("budget_amount")
        BigDecimal budgetAmount,

        @JsonProperty("avg_hourly_rate")
        BigDecimal avgHourlyRate,

        @JsonProperty("slot_hours")
        BigDecimal slotHours,

        @JsonProperty("required_slots")
        long requiredSlots,

        @JsonProperty("calculation")
        String calculation,

        @JsonProperty("warnings")
        List<String> warnings,

        @JsonProperty("position_breakdown")
        List<PositionBreakdown> positionBreakdown
) {
}
