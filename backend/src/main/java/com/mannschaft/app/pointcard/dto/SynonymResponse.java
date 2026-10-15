package com.mannschaft.app.pointcard.dto;

import com.mannschaft.app.pointcard.entity.PointCardProviderEntity;
import com.mannschaft.app.pointcard.entity.PointCardProviderSynonymEntity;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * プロバイダー同義語のレスポンス DTO。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §7.6
 *
 * <p>SystemAdmin 専用同義語管理 UI（{@code AdminPointCardSynonymController}）で利用する。
 * provider の表示名も併記し、UI 側でプロバイダー名のクエリを 2 回投げずに済むようにする。
 */
public record SynonymResponse(
        UUID id,
        UUID providerId,
        String providerDisplayName,
        String synonymDisplay,
        String synonymNormalized,
        String memo,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    /**
     * Entity を DTO に変換する（provider 情報あり）。
     */
    public static SynonymResponse from(
            PointCardProviderSynonymEntity entity,
            PointCardProviderEntity provider) {
        return new SynonymResponse(
                entity.getId(),
                entity.getProviderId(),
                provider != null ? provider.getDisplayName() : null,
                entity.getSynonymDisplay(),
                entity.getSynonymNormalized(),
                entity.getMemo(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
