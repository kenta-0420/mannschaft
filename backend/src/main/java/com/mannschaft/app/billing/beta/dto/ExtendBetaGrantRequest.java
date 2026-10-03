package com.mannschaft.app.billing.beta.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * F20.3 ベータ特典: シスアド 延長リクエスト（TEAM_ORG のみ・設計書 02 §4.3）。
 *
 * <p>{@code extensionMonths} の範囲（1〜24）は<b>本 DTO の {@code @Min}/{@code @Max} が一次ゲート</b>
 * （範囲外は 400・AC-B6）。INDIVIDUAL への延長（無期限ゆえ不可）・取消済み延長は
 * {@code BetaGrantService.extend} が {@code EXTEND_NOT_APPLICABLE}(422) /
 * {@code GRANT_ALREADY_REVOKED}(409) で判定する。</p>
 *
 * @param extensionMonths 延長月数（1〜24）
 */
@Schema(name = "BetaPerkExtendGrantRequest", description = "F20.3 シスアド ベータ特典 延長")
public record ExtendBetaGrantRequest(

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "12")
        @NotNull
        @Min(1)
        @Max(24)
        Integer extensionMonths) {
}
