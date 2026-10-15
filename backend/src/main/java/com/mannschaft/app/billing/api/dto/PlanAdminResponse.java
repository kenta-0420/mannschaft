package com.mannschaft.app.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * F20.1: シスアド プランマスタのレスポンス（enabled=false 含む全件・設計書 02 §4）。
 */
@Getter
@Builder
@Schema(name = "BillingPlanAdminResponse", description = "F20.1 シスアド プランマスタ")
public class PlanAdminResponse {

    @Schema(description = "プランキー（自然キー）", example = "FULL")
    private final String planKey;

    @Schema(description = "表示名 i18n キー")
    private final String displayNameKey;

    @Schema(description = "説明 i18n キー")
    private final String descriptionKey;

    @Schema(description = "基準月額（円）。未定は null", nullable = true, example = "2000")
    private final Integer baseMonthlyPriceJpy;

    @Schema(description = "表示順", example = "10")
    private final int sortOrder;

    @Schema(description = "有効フラグ", example = "true")
    private final boolean enabled;
}
