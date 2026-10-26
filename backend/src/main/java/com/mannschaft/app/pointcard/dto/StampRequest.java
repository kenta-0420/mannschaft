package com.mannschaft.app.pointcard.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * スタンプ押印リクエスト DTO。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §6.2 / §12.1
 *
 * <p>{@code delta} は通常 +1 だが、誤押印取消や特典付与などの運用要件のため
 * 負値 / 複数加算も受け付ける。0 は {@code POINT_CARD_014 STAMP_DELTA_ZERO} で拒否する。
 *
 * @param delta スタンプ増減数（-100〜100、0 は不可）
 * @param memo  任意メモ（最大 200 文字。「特典付与」「誤押印取消」など）
 */
public record StampRequest(
        @NotNull
        @Min(-100)
        @Max(100)
        Integer delta,

        @Size(max = 200)
        String memo
) {
}
