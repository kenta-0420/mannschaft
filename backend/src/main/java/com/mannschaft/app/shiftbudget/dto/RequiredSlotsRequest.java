package com.mannschaft.app.shiftbudget.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.util.List;

/**
 * シフト予算逆算 API のリクエスト DTO。
 *
 * <p>設計書 F08.7 §6.2.2 形状に準拠。</p>
 *
 * @param teamId                 対象チームID（MEMBER_AVG/POSITION_AVG モード時必須）
 * @param budgetAmount           予算額（円, 0 以上）
 * @param slotHours              1スロットあたりの時間 (0.25h 以上 24h 以下)
 * @param rateMode               時給算出モード
 * @param avgHourlyRate          EXPLICIT モード時の固定時給
 * @param positionRequiredCounts POSITION_AVG モード時のポジション別必要人数
 */
public record RequiredSlotsRequest(

        @JsonProperty("team_id")
        Long teamId,

        @NotNull
        @PositiveOrZero
        @JsonProperty("budget_amount")
        BigDecimal budgetAmount,

        @NotNull
        @DecimalMin(value = "0.25", inclusive = true)
        @DecimalMax(value = "24", inclusive = true)
        @JsonProperty("slot_hours")
        BigDecimal slotHours,

        @NotNull
        @JsonProperty("rate_mode")
        RateMode rateMode,

        @PositiveOrZero
        @JsonProperty("avg_hourly_rate")
        BigDecimal avgHourlyRate,

        @Valid
        @JsonProperty("position_required_counts")
        List<PositionRequiredCount> positionRequiredCounts
) {

    /**
     * 平均時給算出モード。設計書 F08.7 §4.1。
     */
    public enum RateMode {
        /** 対象チームの全アクティブメンバーの現行時給を単純平均 */
        MEMBER_AVG,
        /** ポジション別に平均時給を出し、必要人数で重み付け */
        POSITION_AVG,
        /** 呼び出し側が avg_hourly_rate を直接指定 */
        EXPLICIT
    }
}
