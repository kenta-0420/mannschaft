package com.mannschaft.app.billing.beta.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * F20.3 ベータ特典: 条件マスタの取得/upsert レスポンス（設計書 02 §4.6）。
 */
@Getter
@Builder
@Schema(name = "BetaPerkCriteriaResponse", description = "F20.3 ベータ特典 条件マスタ")
public class BetaPerkCriteriaResponse {

    @Schema(description = "ベータ段階", example = "2")
    private final int betaPhase;

    @Schema(description = "付与種別（INDIVIDUAL / TEAM_ORG）", example = "INDIVIDUAL")
    private final String grantKind;

    @Schema(description = "activeDays の評価ウィンドウ（日）", example = "30")
    private final int evaluationWindowDays;

    @Schema(description = "アクティブ日数の下限（null=評価しない）", nullable = true, example = "14")
    private final Integer minActiveDays;

    @Schema(description = "所属経過日数の下限（null=評価しない）", nullable = true, example = "30")
    private final Integer minMembershipTenureDays;

    @Schema(description = "アクティブ人数の下限（TEAM_ORG のみ・null=評価しない）", nullable = true, example = "5")
    private final Integer minActiveMembers;

    @Schema(description = "有効フラグ", example = "true")
    private final boolean enabled;
}
