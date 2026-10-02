package com.mannschaft.app.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * F20.1: カタログ内の 1 プラン（設計書 02 §2.1）。
 */
@Getter
@Builder
@Schema(name = "BillingPlanItem", description = "F20.1 カタログ内の 1 プラン")
public class PlanItem {

    @Schema(description = "プランキー（自然キー）", example = "FULL")
    private final String planKey;

    @Schema(description = "表示名の i18n キー（FE が $t で解決）", example = "billing.plans.full.name")
    private final String displayNameKey;

    @Schema(description = "説明の i18n キー", example = "billing.plans.full.description")
    private final String descriptionKey;

    @Schema(description = "基準月額（円）。未定は null（ベータ計測後に決定）", nullable = true, example = "2000")
    private final Integer baseMonthlyPriceJpy;

    @Schema(description = "このプランで解放される機能一覧")
    private final List<FeatureItem> features;

    @Schema(description = "人数バンド別単価（TEAM/ORG のみ・空配列可）")
    private final List<PriceBand> priceBands;
}
