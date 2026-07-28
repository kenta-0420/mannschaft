package com.mannschaft.app.billing;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * F20.1: 402（{@code FEATURE_NOT_ENTITLED}）応答の details に載せる購入導線情報。
 *
 * <p>{@link EntitlementGuard#require} が未充足＋購入可能と判定した際に組み立てる。
 * {@code GlobalExceptionHandler} の共通 {@link com.mannschaft.app.common.ErrorResponse} /
 * {@link com.mannschaft.app.common.ErrorResponse.ErrorDetail} は変更せず、専用ハンドラが
 * このオブジェクトを {@code error.details} に載せて返す（案B・後方互換バイト不変）。</p>
 *
 * <p>{@code @Schema(name=...)} で nested schema 名の衝突を回避する
 * （memory {@code feedback_openapi_nested_schema_name_collision}）。</p>
 */
@Getter
@Builder
@Schema(name = "BillingEntitlementNotEntitledDetails", description = "F20.1 402 応答の購入導線情報")
public class EntitlementNotEntitledDetails {

    @Schema(description = "権利が不足している機能キー", example = "ads.hide")
    private final String featureKey;

    @Schema(description = "アドオン契約で購入可能か", example = "true")
    private final boolean addonAvailable;

    @Schema(description = "アドオン月額（円）。未定/アドオン不可の場合は null", nullable = true, example = "500")
    private final Integer addonPriceJpy;

    @Schema(description = "この機能を含む購入可能プラン（enabled かつ非 FREE）のキー一覧。0件の場合は空配列")
    private final List<String> plansContaining;

    @Schema(description = "スコープ種別（USER / TEAM / ORG）", example = "TEAM")
    private final String scopeKind;

    @Schema(description = "スコープ ID", example = "123")
    private final Long scopeId;
}
