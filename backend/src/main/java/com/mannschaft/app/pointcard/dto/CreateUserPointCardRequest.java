package com.mannschaft.app.pointcard.dto;

import com.mannschaft.app.pointcard.enums.BarcodeFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * カード追加リクエスト DTO。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §6.4
 *
 * <p>必須項目は {@code displayName} / {@code barcodeValue} / {@code barcodeFormat} の 3 つのみ。
 * {@code provider_id} はサーバー側で fuzzy match により自動解決する（クライアント送信不可）。
 */
public record CreateUserPointCardRequest(
        @NotBlank(message = "displayName は必須です")
        @Size(max = 128, message = "displayName は 128 文字以内で指定してください")
        String displayName,

        @NotBlank(message = "barcodeValue は必須です")
        @Size(max = 256, message = "barcodeValue は 256 文字以内で指定してください")
        String barcodeValue,

        @NotNull(message = "barcodeFormat は必須です")
        BarcodeFormat barcodeFormat,

        @Size(max = 64, message = "nickname は 64 文字以内で指定してください")
        String nickname,

        @Size(max = 255, message = "memo は 255 文字以内で指定してください")
        String memo,

        Boolean favorite
) {
}
