package com.mannschaft.app.billing.beta.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * F20.3 ベータ特典: 付与条件の充足状況（API 表現・設計書 02 §1.1）。
 *
 * <p>{@code /me/beta-perks} の {@code eligibility}。現行フェーズの criteria 未定義/{@code enabled=false} 時は
 * レスポンスで {@code null}（{@code CRITERIA_NOT_FOUND} を catch して null にする・AC-N4）。</p>
 */
@Getter
@Builder
@Schema(name = "BetaPerkEligibilityStatus", description = "F20.3 付与条件の充足状況")
public class EligibilityStatus {

    @Schema(description = "評価対象のベータ段階", example = "2")
    private final int betaPhase;

    @Schema(description = "全ての定義済み指標を満たすか", example = "false")
    private final boolean eligible;

    @Schema(description = "指標ごとの進捗（定義済み指標のみ）")
    private final List<MetricProgressDto> metrics;
}
