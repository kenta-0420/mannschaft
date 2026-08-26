package com.mannschaft.app.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * F20.1: スコープの権利サマリ（現在の契約と有効機能・設計書 02 §2.2）。
 *
 * <p>{@code entitledFeatures} は entitlements 行由来（PLAN/ADDON/BETA_GRANT）に加えて
 * FREE 掲載機能・非営利無料枠の virtual エントリを合成し、<b>「利用できる機能」一覧 ＝
 * {@code isEntitled=true} の集合</b>を保証する（M-2・AC-23）。合成は
 * {@code EntitlementQueryService.entitledFeatureKeys} を正準として組み立てる。</p>
 */
@Getter
@Builder
@Schema(name = "BillingEntitlementSummaryResponse", description = "F20.1 スコープの権利サマリ")
public class EntitlementSummaryResponse {

    @Schema(description = "スコープ種別（USER / TEAM / ORG）", example = "TEAM")
    private final String scopeKind;

    @Schema(description = "スコープ ID", example = "123")
    private final Long scopeId;

    @Schema(description = "現在アクティブな PLAN 契約。無契約は null", nullable = true)
    private final ActiveContract activePlan;

    @Schema(description = "現在アクティブな ADDON 契約一覧（空配列可）")
    private final List<ActiveContract> activeAddons;

    @Schema(description = "利用できる機能一覧（isEntitled=true の集合・virtual 合成込み）")
    private final List<EntitledFeature> entitledFeatures;
}
