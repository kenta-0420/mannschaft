package com.mannschaft.app.shift.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;

/**
 * 自動割当パラメータDTO。
 *
 * @param preferenceWeight         希望スコアの重み（デフォルト 0.6）
 * @param fairnessWeight           公平性スコアの重み（デフォルト 0.3）
 * @param consecutivePenaltyWeight 連続勤務ペナルティの重み（デフォルト 0.1）
 * @param respectWorkConstraints   勤務制約を考慮するか（デフォルト true）
 * @param overwriteExisting        既存の割当を上書きするか（デフォルト false）
 */
public record AssignmentParametersDto(
        @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal preferenceWeight,
        @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal fairnessWeight,
        @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal consecutivePenaltyWeight,
        Boolean respectWorkConstraints,
        Boolean overwriteExisting
) {

    /**
     * デフォルトパラメータを返す。
     */
    public static AssignmentParametersDto defaults() {
        return new AssignmentParametersDto(
                new BigDecimal("0.6"),
                new BigDecimal("0.3"),
                new BigDecimal("0.1"),
                true,
                false
        );
    }

    /**
     * null フィールドをデフォルト値で補完したインスタンスを返す。
     */
    public AssignmentParametersDto withDefaults() {
        AssignmentParametersDto d = defaults();
        return new AssignmentParametersDto(
                preferenceWeight != null ? preferenceWeight : d.preferenceWeight(),
                fairnessWeight != null ? fairnessWeight : d.fairnessWeight(),
                consecutivePenaltyWeight != null ? consecutivePenaltyWeight : d.consecutivePenaltyWeight(),
                respectWorkConstraints != null ? respectWorkConstraints : d.respectWorkConstraints(),
                overwriteExisting != null ? overwriteExisting : d.overwriteExisting()
        );
    }
}
