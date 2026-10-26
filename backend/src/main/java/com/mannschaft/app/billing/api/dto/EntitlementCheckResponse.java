package com.mannschaft.app.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * F20.1: 単一機能の判定結果（FE ゲート補助・BE が正・設計書 02 §2.3）。
 *
 * <p><b>FE がこの結果だけで機能を解放してはならない</b>（BE の {@code EntitlementGuard} が常に正・03 §4）。
 * 表示の出し分け（ペイウォールモーダルの購入導線）専用。</p>
 */
@Getter
@Builder
@Schema(name = "BillingEntitlementCheckResponse", description = "F20.1 単一機能の判定結果（表示出し分け専用）")
public class EntitlementCheckResponse {

    @Schema(description = "権利があるか（BE 判定と一致）", example = "false")
    private final boolean entitled;

    @Schema(description = "判定した機能キー", example = "ads.hide")
    private final String featureKey;

    @Schema(description = "購入手段があるか（アドオン/有料プラン掲載）", example = "true")
    private final boolean purchasable;

    @Schema(description = "アドオン月額（円）。addon 不可・未定は null", nullable = true, example = "300")
    private final Integer addonPriceJpy;

    @Schema(description = "この機能を掲載する購入可能プランのキー一覧（空配列可）", example = "[\"BASIC\",\"FULL\"]")
    private final List<String> plansContaining;
}
