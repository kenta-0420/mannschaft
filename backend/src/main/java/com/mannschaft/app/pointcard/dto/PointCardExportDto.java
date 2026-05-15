package com.mannschaft.app.pointcard.dto;

import com.mannschaft.app.pointcard.entity.UserPointCardEntity;
import com.mannschaft.app.pointcard.enums.BarcodeFormat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * GDPR エクスポート用 DTO（F12.3 §3.2 連携）。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §10
 *
 * <p>{@link UserPointCardEntity} の暗号化フィールド（displayName / nickname /
 * barcodeValue / memo）は {@code EncryptedStringConverter} が SELECT 時に
 * 透過的に復号するため、本 DTO は復号後の平文をそのまま受け取る形で構築する。
 * これにより GDPR 第 15 条のアクセス権（本人が自分のデータを取得する権利）を
 * 実現する。
 *
 * <p>{@code providerId} は NULL 許容（自由入力カードはマッチなし）。
 * Phase 2 用の {@code balance} / {@code stampCount} は Phase 1 では常に NULL。
 */
public record PointCardExportDto(
        UUID id,
        Long userId,
        UUID providerId,
        String displayName,
        String nickname,
        String barcodeValue,
        BarcodeFormat barcodeFormat,
        String last4,
        String memo,
        boolean favorite,
        int displayOrder,
        BigDecimal balance,
        Integer stampCount,
        OffsetDateTime lastUsedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public static PointCardExportDto from(UserPointCardEntity card) {
        return new PointCardExportDto(
                card.getId(),
                card.getUserId(),
                card.getProviderId(),
                card.getDisplayName(),
                card.getNickname(),
                card.getBarcodeValue(),
                card.getBarcodeFormat(),
                card.getLast4(),
                card.getMemo(),
                card.isFavorite(),
                card.getDisplayOrder(),
                card.getBalance(),
                card.getStampCount(),
                card.getLastUsedAt(),
                card.getCreatedAt(),
                card.getUpdatedAt()
        );
    }
}
