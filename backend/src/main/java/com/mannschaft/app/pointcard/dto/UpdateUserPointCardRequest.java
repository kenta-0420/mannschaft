package com.mannschaft.app.pointcard.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * カード更新（PATCH）リクエスト DTO。全フィールド optional（null は既存値維持）。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §6.4
 *
 * <p>{@code displayName} を変更した場合はサーバー側で {@code provider_id} を再 fuzzy match する。
 * {@code barcodeValue} / {@code barcodeFormat} はセキュリティ上の理由から更新不可
 * （変更したい場合は削除 → 再作成）。
 */
public record UpdateUserPointCardRequest(
        @Size(max = 128, message = "displayName は 128 文字以内で指定してください")
        String displayName,

        @Size(max = 64, message = "nickname は 64 文字以内で指定してください")
        String nickname,

        @Size(max = 255, message = "memo は 255 文字以内で指定してください")
        String memo,

        Boolean favorite,

        @Min(value = 0, message = "displayOrder は 0 以上で指定してください")
        Integer displayOrder
) {
}
