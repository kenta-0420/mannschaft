package com.mannschaft.app.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * F20.1: 人数バンド別単価の 1 バンド（設計書 02 §2.1）。
 */
@Getter
@Builder
@Schema(name = "BillingPriceBand", description = "F20.1 人数バンド別単価の 1 バンド")
public class PriceBand {

    @Schema(description = "スコープ種別（TEAM / ORG）", example = "TEAM")
    private final String scopeKind;

    @Schema(description = "バンド番号（1〜・昇順）", example = "2")
    private final int bandNo;

    @Schema(description = "アクティブ人数下限（この値以上）", example = "21")
    private final int minMembers;

    @Schema(description = "アクティブ人数上限（この値以下）。無制限は null", nullable = true, example = "50")
    private final Integer maxMembers;

    @Schema(description = "月額（円）。未定は null", nullable = true, example = "5000")
    private final Integer monthlyPriceJpy;
}
