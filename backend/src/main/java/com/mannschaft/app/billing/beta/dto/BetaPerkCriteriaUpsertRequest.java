package com.mannschaft.app.billing.beta.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * F20.3 ベータ特典: 条件マスタ upsert リクエスト（設計書 02 §4.6）。
 *
 * <p><b>最低 1 指標が非 NULL 必須</b>（全 NULL の「無条件付与」は
 * {@code CRITERIA_VALIDATION_FAILED}(400)・AC-N2）は {@code BetaPerkCriteriaService} が判定する
 * （複数フィールドにまたがる相関チェックのため DTO 単項ではなくサービスで検証）。
 * {@code evaluationWindowDays} の範囲（1〜365）は本 DTO の {@code @Min}/{@code @Max} が一次ゲート。</p>
 *
 * @param evaluationWindowDays     activeDays の評価ウィンドウ（日・1〜365・必須）
 * @param minActiveDays            アクティブ日数の下限（NULL=評価しない）
 * @param minMembershipTenureDays  所属経過日数の下限（NULL=評価しない）
 * @param minActiveMembers         アクティブ人数の下限（TEAM_ORG のみ意味を持つ・NULL=評価しない）
 * @param enabled                  false=このフェーズ×種別の付与を停止
 */
@Schema(name = "BetaPerkCriteriaUpsertRequest", description = "F20.3 シスアド ベータ特典 条件マスタ upsert")
public record BetaPerkCriteriaUpsertRequest(

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "30")
        @NotNull
        @Min(1)
        @Max(365)
        Integer evaluationWindowDays,

        @Schema(nullable = true, example = "14")
        @Min(0)
        Integer minActiveDays,

        @Schema(nullable = true, example = "30")
        @Min(0)
        Integer minMembershipTenureDays,

        @Schema(nullable = true, example = "5")
        @Min(0)
        Integer minActiveMembers,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "true")
        @NotNull
        Boolean enabled) {

    /** 少なくとも 1 指標が非 NULL か（無条件付与でないか・設計書 02 §4.6・AC-N2）。 */
    public boolean hasAnyMetric() {
        return minActiveDays != null || minMembershipTenureDays != null || minActiveMembers != null;
    }
}
