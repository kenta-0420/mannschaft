package com.mannschaft.app.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * F20.1: シスアド 機能カタログ upsert リクエスト（設計書 02 §4・§6）。
 *
 * <p>{@code {featureKey}} は PATH（自然キー）で受ける。{@code category=REVENUE} かつ
 * {@code freeForNonprofit=true} は Service 層で 400（{@code ENTITLEMENT_010}・README 原則）。
 *
 * @param category         区分（INTERNAL / REVENUE）
 * @param addonAvailable   アドオン単体契約可否
 * @param addonPriceJpy    アドオン月額（円）。未定は null
 * @param freeForNonprofit 非営利無料開放
 * @param displayNameKey   表示名 i18n キー
 * @param descriptionKey   説明 i18n キー
 * @param sortOrder        表示順
 * @param enabled          有効フラグ（false=カタログ非表示＋isEntitled 常に false）
 */
@Schema(name = "BillingFeatureUpsertRequest", description = "F20.1 シスアド 機能カタログ upsert")
public record FeatureUpsertRequest(

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "INTERNAL")
        @NotBlank
        String category,

        @Schema(example = "true")
        boolean addonAvailable,

        @Schema(nullable = true, example = "300")
        Integer addonPriceJpy,

        @Schema(example = "false")
        boolean freeForNonprofit,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        String displayNameKey,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        String descriptionKey,

        @Schema(example = "10")
        int sortOrder,

        @Schema(example = "true")
        boolean enabled) {
}
