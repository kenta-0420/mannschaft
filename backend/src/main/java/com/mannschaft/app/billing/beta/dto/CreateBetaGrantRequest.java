package com.mannschaft.app.billing.beta.dto;

import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.beta.GrantKind;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * F20.3 ベータ特典: シスアド 手動付与リクエスト（設計書 02 §4.1）。
 *
 * <p>{@code grantKind × scopeKind} の整合（AC-A1）・{@code betaPhase} 範囲（AC-B5）は
 * {@code BetaGrantService.grantBetaPerk} が {@code GRANT_SCOPE_MISMATCH}(422) /
 * {@code BETA_PHASE_INVALID}(400) で判定するため、ここでは enum バインド（不正値 400）と
 * {@code @NotNull} の一次ゲートのみ行う。{@code organizationId} は API 層が
 * {@code resolveOrganizationId} で解決してサービスへ渡す（DTO では受けない・設計書 01 §1）。</p>
 *
 * @param grantKind        INDIVIDUAL / TEAM_ORG（不正値は Jackson バインド失敗で 400）
 * @param betaPhase        ベータ段階（1〜4・範囲外は {@code BETA_PHASE_INVALID} 400）
 * @param scopeKind        USER / TEAM / ORG
 * @param scopeId          users.id / teams.id / organizations.id
 * @param skipCriteriaCheck 未達でも付与するマスター運用の例外（既定 false・null は false 扱い）
 * @param note             監査用メモ（任意・500 文字以内）
 */
@Schema(name = "BetaPerkCreateGrantRequest", description = "F20.3 シスアド ベータ特典 手動付与")
public record CreateBetaGrantRequest(

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "TEAM_ORG")
        @NotNull
        GrantKind grantKind,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "2")
        @NotNull
        Integer betaPhase,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "TEAM")
        @NotNull
        EntitlementScopeKind scopeKind,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "123")
        @NotNull
        Long scopeId,

        @Schema(nullable = true, description = "criteria 未達でも付与する例外運用（既定 false）", example = "false")
        Boolean skipCriteriaCheck,

        @Schema(nullable = true, example = "第2期 パイロット団体")
        @Size(max = 500)
        String note) {

    /** {@code skipCriteriaCheck} の null を false に正規化する（既定 false・設計書 02 §4.1）。 */
    public boolean skipCriteriaCheckOrDefault() {
        return Boolean.TRUE.equals(skipCriteriaCheck);
    }
}
