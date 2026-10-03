package com.mannschaft.app.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * F20.1: 機能カタログの 1 機能（設計書 02 §2.1 の共通 DTO・カタログ/サマリ双方で使用）。
 */
@Getter
@Builder
@Schema(name = "BillingFeatureItem", description = "F20.1 機能カタログの 1 機能")
public class FeatureItem {

    @Schema(description = "機能キー", example = "reservation.notification_recipients_extended")
    private final String featureKey;

    @Schema(description = "区分（INTERNAL / REVENUE）", example = "INTERNAL")
    private final String category;

    @Schema(description = "アドオン単体契約が可能か", example = "true")
    private final boolean addonAvailable;

    @Schema(description = "アドオン月額（円）。未定は null", nullable = true, example = "300")
    private final Integer addonPriceJpy;

    @Schema(description = "表示名の i18n キー")
    private final String displayNameKey;

    @Schema(description = "説明の i18n キー")
    private final String descriptionKey;

    @Schema(description = "表示順（昇順）", example = "10")
    private final int sortOrder;
}
