package com.mannschaft.app.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * F20.1: 人数バンド一括置換リクエスト（設計書 02 §4）。
 *
 * <p>バンドは {@code bandNo} 昇順で {@code minMembers = 前バンド maxMembers + 1}・
 * 最終バンドのみ {@code maxMembers=null} を許可。違反は 400（{@code ENTITLEMENT_010}）。
 *
 * @param bands 置換後のバンド一覧
 */
@Schema(name = "BillingPriceBandsReplaceRequest", description = "F20.1 人数バンド一括置換")
public record PriceBandsReplaceRequest(

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        List<PriceBandInput> bands) {

    /**
     * バンド 1 件の入力（record ゆえ Jackson creator 自動）。
     *
     * @param scopeKind       スコープ種別（TEAM / ORG）
     * @param bandNo          バンド番号（1〜昇順）
     * @param minMembers      人数下限
     * @param maxMembers      人数上限（無制限は null・最終バンドのみ）
     * @param monthlyPriceJpy 月額（円・未定は null）
     */
    @Schema(name = "BillingPriceBandInput", description = "F20.1 バンド 1 件の入力")
    public record PriceBandInput(

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "TEAM")
            String scopeKind,

            @Schema(example = "1")
            short bandNo,

            @Schema(example = "1")
            int minMembers,

            @Schema(nullable = true, example = "20")
            Integer maxMembers,

            @Schema(nullable = true, example = "3000")
            Integer monthlyPriceJpy) {
    }
}
