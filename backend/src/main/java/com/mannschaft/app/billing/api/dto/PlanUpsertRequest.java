package com.mannschaft.app.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * F20.1: シスアド プランマスタ upsert リクエスト（設計書 02 §4・§6）。
 *
 * <p>{@code {planKey}} は PATH（自然キー）で受けるため body には含めない
 * （{@code fee_policies} の CRUD 様式）。
 *
 * @param displayNameKey     表示名 i18n キー
 * @param descriptionKey     説明 i18n キー
 * @param baseMonthlyPriceJpy 基準月額（円）。未定は null
 * @param sortOrder          表示順
 * @param enabled            有効フラグ（false=新規契約不可）
 */
@Schema(name = "BillingPlanUpsertRequest", description = "F20.1 シスアド プランマスタ upsert")
public record PlanUpsertRequest(

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "billing.plans.full.name")
        @NotBlank
        String displayNameKey,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "billing.plans.full.description")
        @NotBlank
        String descriptionKey,

        @Schema(nullable = true, example = "2000")
        Integer baseMonthlyPriceJpy,

        @Schema(example = "10")
        int sortOrder,

        @Schema(example = "true")
        boolean enabled) {
}
