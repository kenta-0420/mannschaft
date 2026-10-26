package com.mannschaft.app.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * F20.1: シスアド 機能カタログのレスポンス（enabled=false 含む全件・設計書 02 §4）。
 */
@Getter
@Builder
@Schema(name = "BillingFeatureAdminResponse", description = "F20.1 シスアド 機能カタログ")
public class FeatureAdminResponse {

    @Schema(description = "機能キー（自然キー）", example = "ads.hide")
    private final String featureKey;

    @Schema(description = "区分（INTERNAL / REVENUE）", example = "REVENUE")
    private final String category;

    @Schema(description = "アドオン単体契約可否", example = "true")
    private final boolean addonAvailable;

    @Schema(description = "アドオン月額（円）。未定は null", nullable = true, example = "300")
    private final Integer addonPriceJpy;

    @Schema(description = "非営利無料開放", example = "false")
    private final boolean freeForNonprofit;

    @Schema(description = "表示名 i18n キー")
    private final String displayNameKey;

    @Schema(description = "説明 i18n キー")
    private final String descriptionKey;

    @Schema(description = "表示順", example = "10")
    private final int sortOrder;

    @Schema(description = "有効フラグ", example = "true")
    private final boolean enabled;
}
