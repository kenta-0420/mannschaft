package com.mannschaft.app.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * F20.1: {@code ENTITLEMENT_003}(402) 応答の購入導線 details（設計書 02 §1.2・04 §2 U-2）。
 *
 * <p>{@code EntitlementGuard} が 402 を投げる際に {@code BusinessException.withDetails} で運搬し、
 * {@code GlobalExceptionHandler} が {@code error.details} として応答に含める。FE ペイウォールモーダルは
 * この details から「どの機能で 402 になったか」「アドオン/どのプランで解放できるか」を描画する
 * （ベータ中は価格未定＝null のため「無料で有効にする」ワンクリック CTA・04 §2 L-1）。</p>
 *
 * <p><b>403（{@code ENTITLEMENT_004}）には付与しない</b>: 購入不可の機能に購入導線を出さない設計
 * （02 §1.2・AC-18 fail-safe）。</p>
 */
@Schema(name = "EntitlementNotEntitledDetails",
        description = "F20.1 ENTITLEMENT_003(402) の購入導線情報（error.details）。FE ペイウォールモーダルの描画に使う。")
public record EntitlementNotEntitledDetails(

        @Schema(description = "402 になった機能キー", example = "ads.hide")
        String featureKey,

        @Schema(description = "アドオン単体で購入可能か", example = "true")
        boolean addonAvailable,

        @Schema(description = "アドオン月額（円）。addon 不可・価格未定（ベータ中）は null",
                nullable = true, example = "300")
        Integer addonPriceJpy,

        @Schema(description = "この機能を掲載する購入可能プラン（enabled・非 FREE）のキー一覧（空配列可）",
                example = "[\"BASIC\",\"FULL\"]")
        List<String> plansContaining) {
}
