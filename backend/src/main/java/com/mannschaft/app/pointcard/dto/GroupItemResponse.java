package com.mannschaft.app.pointcard.dto;

import com.mannschaft.app.pointcard.enums.BarcodeFormat;

import java.util.UUID;

/**
 * グループ詳細内の 1 アイテム（カード復号値含む）。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §6.2 / §6 (Groups API)
 *
 * <p>提示モードで連続スワイプして表示するため、バーコード値・形式・プロバイダー情報を
 * 1 レスポンスに含める。プロバイダー未マッチカード（自由入力）は {@code providerCode} 等が null。
 */
public record GroupItemResponse(
        UUID cardId,
        int displayOrder,
        String displayName,
        String nickname,
        String barcodeValue,
        BarcodeFormat barcodeFormat,
        String last4,
        UUID providerId,
        String providerCode,
        String providerDisplayName,
        String providerBrandColor,
        String providerLogoUrl,
        boolean providerMatched
) {
}
