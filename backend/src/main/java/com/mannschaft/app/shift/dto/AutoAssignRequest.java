package com.mannschaft.app.shift.dto;

import com.mannschaft.app.shift.AssignmentStrategyType;
import jakarta.validation.constraints.NotNull;

/**
 * 自動割当実行リクエストDTO。
 *
 * @param strategy   使用するアルゴリズム種別
 * @param parameters 割当パラメータ（null の場合はデフォルト値を使用）
 */
public record AutoAssignRequest(
        @NotNull AssignmentStrategyType strategy,
        AssignmentParametersDto parameters
) {}
