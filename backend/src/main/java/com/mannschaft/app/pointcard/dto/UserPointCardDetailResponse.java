package com.mannschaft.app.pointcard.dto;

import com.mannschaft.app.pointcard.entity.PointCardProviderEntity;
import com.mannschaft.app.pointcard.entity.UserPointCardEntity;
import com.mannschaft.app.pointcard.enums.BarcodeFormat;
import com.mannschaft.app.pointcard.enums.PointCardProviderType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * カード詳細レスポンス DTO（提示モード / 編集モーダル用）。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §6.4
 *
 * <p>一覧 DTO の全フィールドに加え、復号した
 * {@code barcodeValue} / {@code nickname} / {@code memo} を返す。
 * このため認証必須 + レート制限 120/min（{@code PointCardRateLimitFilter}）で
 * 大量取得を防御する。{@code providerMatched} はクライアントが
 * 「プロバイダー手動設定 UI」を出すか判定するために返す。
 *
 * <p>Phase 3 で追加: {@code providerType} / {@code providerOrganizationId} /
 * {@code stampCount} / {@code balance}。残高型・スタンプ型カードの詳細画面で
 * 現在残高 / スタンプ数の表示と、提示モード時の UI 分岐に使う。
 */
public record UserPointCardDetailResponse(
        UUID id,
        UUID providerId,
        String providerCode,
        String providerDisplayName,
        String providerBrandColor,
        String providerLogoUrl,
        boolean providerMatched,
        String displayName,
        String nickname,
        String barcodeValue,
        BarcodeFormat barcodeFormat,
        String last4,
        String memo,
        boolean favorite,
        int displayOrder,
        OffsetDateTime lastUsedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        PointCardProviderType providerType,
        Long providerOrganizationId,
        Integer stampCount,
        BigDecimal balance
) {

    public static UserPointCardDetailResponse from(UserPointCardEntity card,
                                                   PointCardProviderEntity provider) {
        return new UserPointCardDetailResponse(
                card.getId(),
                provider != null ? provider.getId() : null,
                provider != null ? provider.getCode() : null,
                provider != null ? provider.getDisplayName() : null,
                provider != null ? provider.getBrandColor() : null,
                provider != null ? provider.getLogoUrl() : null,
                provider != null,
                card.getDisplayName(),
                card.getNickname(),
                card.getBarcodeValue(),
                card.getBarcodeFormat(),
                card.getLast4(),
                card.getMemo(),
                card.isFavorite(),
                card.getDisplayOrder(),
                card.getLastUsedAt(),
                card.getCreatedAt(),
                card.getUpdatedAt(),
                provider != null ? provider.getType() : null,
                provider != null ? provider.getOrganizationId() : null,
                card.getStampCount(),
                card.getBalance()
        );
    }
}
