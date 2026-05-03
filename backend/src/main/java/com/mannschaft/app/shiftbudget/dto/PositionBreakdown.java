package com.mannschaft.app.shiftbudget.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * POSITION_AVG モードのポジション別寄与度内訳。
 *
 * <p>設計書 F08.7 §4.1 「レスポンスに position_breakdown 配列を含める」に準拠。
 * 各ポジションの平均時給 / 必要人数 / 寄与重みを可視化する。</p>
 *
 * @param positionId    シフトポジションID
 * @param avgRate       当該ポジションのアクティブメンバー時給平均（メンバー不在時は null）
 * @param memberCount   集計に含まれた時給設定済アクティブメンバー数
 * @param requiredCount 当該ポジションでの必要人数（リクエスト指定値）
 */
public record PositionBreakdown(

        @JsonProperty("position_id")
        Long positionId,

        @JsonProperty("avg_rate")
        BigDecimal avgRate,

        @JsonProperty("member_count")
        Integer memberCount,

        @JsonProperty("required_count")
        Integer requiredCount
) {
}
