package com.mannschaft.app.shiftbudget.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * POSITION_AVG モードのポジション別必要人数指定。
 *
 * <p>設計書 F08.7 §4.1 v1.1 / v1.2 確定形。</p>
 *
 * @param positionId    シフトポジションID ({@code shift_positions.id})
 * @param requiredCount 当該ポジションで必要なメンバー数（1 以上）
 */
public record PositionRequiredCount(

        @NotNull
        @JsonProperty("position_id")
        Long positionId,

        @NotNull
        @Positive
        @JsonProperty("required_count")
        Integer requiredCount
) {
}
