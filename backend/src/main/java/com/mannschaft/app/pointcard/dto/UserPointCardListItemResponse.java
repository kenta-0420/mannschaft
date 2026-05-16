package com.mannschaft.app.pointcard.dto;

import com.mannschaft.app.pointcard.entity.PointCardProviderEntity;
import com.mannschaft.app.pointcard.entity.UserPointCardEntity;
import com.mannschaft.app.pointcard.enums.BarcodeFormat;
import com.mannschaft.app.pointcard.enums.PointCardProviderType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * カード一覧用 DTO。肩越し閲覧（shoulder surfing）リスク回避のため
 * {@code barcodeValue} / {@code nickname} / {@code memo} は **返さない**。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §5.2 / §6.4
 *
 * <p>{@code last4} だけは識別性が高くリスクが小さいため平文で返す。
 * 一覧画面でユーザーが「あ、これは違う」と即座に気付けるよう、
 * {@code displayName} は復号後の値を返す。
 *
 * <p>Phase 3 で追加: {@code providerType} / {@code providerOrganizationId} /
 * {@code stampCount} / {@code balance}。残高型・スタンプ型カードの一覧描画で
 * 現在残高 / スタンプ数を即時表示するためにフロント側へ公開する。
 */
public record UserPointCardListItemResponse(
        UUID id,
        UUID providerId,
        String providerCode,
        String providerDisplayName,
        String providerBrandColor,
        String providerLogoUrl,
        String displayName,
        String last4,
        BarcodeFormat barcodeFormat,
        boolean favorite,
        int displayOrder,
        OffsetDateTime lastUsedAt,
        OffsetDateTime createdAt,
        PointCardProviderType providerType,
        Long providerOrganizationId,
        Integer stampCount,
        BigDecimal balance
) {

    /**
     * Entity と紐付くプロバイダー（null 可）から一覧 DTO を構築する。
     */
    public static UserPointCardListItemResponse from(UserPointCardEntity card,
                                                     PointCardProviderEntity provider) {
        return new UserPointCardListItemResponse(
                card.getId(),
                provider != null ? provider.getId() : null,
                provider != null ? provider.getCode() : null,
                provider != null ? provider.getDisplayName() : null,
                provider != null ? provider.getBrandColor() : null,
                provider != null ? provider.getLogoUrl() : null,
                card.getDisplayName(),
                card.getLast4(),
                card.getBarcodeFormat(),
                card.isFavorite(),
                card.getDisplayOrder(),
                card.getLastUsedAt(),
                card.getCreatedAt(),
                provider != null ? provider.getType() : null,
                provider != null ? provider.getOrganizationId() : null,
                card.getStampCount(),
                card.getBalance()
        );
    }
}
