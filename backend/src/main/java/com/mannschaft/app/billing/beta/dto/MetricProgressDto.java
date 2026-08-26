package com.mannschaft.app.billing.beta.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * F20.3 ベータ特典: 付与条件 1 指標あたりの進捗（API 表現・設計書 02 §1）。
 *
 * <p>内部モデル {@code com.mannschaft.app.billing.beta.MetricProgress} の API 用 DTO。
 * ADHD フレンドリーな「あと N 日で達成」表示（04 §1）の原資。{@code @Schema(name=)} を
 * 内部モデルと分離して OpenAPI 名衝突を回避する（memory {@code feedback_openapi_nested_schema_name_collision}）。</p>
 */
@Getter
@Builder
@Schema(name = "BetaPerkMetricProgress", description = "F20.3 付与条件の指標進捗")
public class MetricProgressDto {

    @Schema(description = "指標キー", example = "activeDays")
    private final String metricKey;

    @Schema(description = "実測値", example = "9")
    private final long actual;

    @Schema(description = "閾値（この値以上で達成）", example = "14")
    private final long required;
}
