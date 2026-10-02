package com.mannschaft.app.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * F20.1: プラン→機能の一括置換リクエスト（設計書 02 §4）。
 *
 * <p>各 featureKey が {@code feature_catalog} に実在しなければ 400（{@code ENTITLEMENT_010}）。
 *
 * @param featureKeys 置換後の機能キー一覧
 */
@Schema(name = "BillingPlanFeaturesReplaceRequest", description = "F20.1 プラン→機能一括置換")
public record PlanFeaturesReplaceRequest(

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "[\"ads.hide\",\"template.premium_modules\"]")
        @NotNull
        List<String> featureKeys) {
}
