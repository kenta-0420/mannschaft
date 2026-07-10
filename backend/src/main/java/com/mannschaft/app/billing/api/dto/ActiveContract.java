package com.mannschaft.app.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * F20.1: 権利サマリ内のアクティブ契約（PLAN または ADDON・設計書 02 §2.2）。
 */
@Getter
@Builder
@Schema(name = "BillingActiveContract", description = "F20.1 権利サマリ内のアクティブ契約")
public class ActiveContract {

    @Schema(description = "契約 ID（UUID）", example = "0198aaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
    private final String contractId;

    @Schema(description = "プランキー（PLAN 契約時）。ADDON 時は null", nullable = true, example = "FULL")
    private final String planKey;

    @Schema(description = "機能キー（ADDON 契約時）。PLAN 時は null", nullable = true, example = "ads.hide")
    private final String featureKey;

    @Schema(description = "契約日時（ISO-8601）")
    private final LocalDateTime contractedAt;

    @Schema(description = "契約時単価スナップショット（円）。ベータ中は null（無償）", nullable = true)
    private final Integer priceJpySnapshot;
}
