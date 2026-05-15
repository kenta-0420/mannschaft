package com.mannschaft.app.pointcard.dto;

import com.mannschaft.app.pointcard.entity.PointCardProviderEntity;
import com.mannschaft.app.pointcard.enums.BarcodeFormat;
import com.mannschaft.app.pointcard.enums.PointCardCategory;
import com.mannschaft.app.pointcard.enums.PointCardProviderType;

import java.util.UUID;

/**
 * プロバイダー一覧 / 詳細 API のレスポンス DTO。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §6.2
 *
 * <p>暗号化対象フィールドは持たず、運営マスタとして公開可能な属性のみを返す。
 */
public record PointCardProviderResponse(
        UUID id,
        String code,
        String displayName,
        PointCardCategory category,
        PointCardProviderType type,
        Long organizationId,
        String logoUrl,
        String brandColor,
        BarcodeFormat defaultBarcodeFormat,
        String cardNumberLengthHint,
        String legalNotice,
        boolean isActive
) {

    public static PointCardProviderResponse from(PointCardProviderEntity entity) {
        return new PointCardProviderResponse(
                entity.getId(),
                entity.getCode(),
                entity.getDisplayName(),
                entity.getCategory(),
                entity.getType(),
                entity.getOrganizationId(),
                entity.getLogoUrl(),
                entity.getBrandColor(),
                entity.getDefaultBarcodeFormat(),
                entity.getCardNumberLengthHint(),
                entity.getLegalNotice(),
                Boolean.TRUE.equals(entity.getActive())
        );
    }
}
