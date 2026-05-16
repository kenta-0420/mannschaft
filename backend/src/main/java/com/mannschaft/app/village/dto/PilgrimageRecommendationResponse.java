package com.mannschaft.app.village.dto;

import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillagePilgrimageRecommendationEntity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 巡礼推薦の API レスポンス（F17.1 Phase 3-β）。
 *
 * <p>{@code village} は推薦された村のメタデータ（slug / name / category / icon）を埋め込む。
 * バッチ生成直後の単発取得・履歴一覧の両方で使う。</p>
 */
public record PilgrimageRecommendationResponse(
        UUID id,
        UUID villageId,
        String villageSlug,
        String villageName,
        String villageCategory,
        String villageIconR2Key,
        LocalDate recommendedDate,
        String reason,
        LocalDateTime visitedAt
) {
    public static PilgrimageRecommendationResponse of(
            VillagePilgrimageRecommendationEntity entity,
            VillageEntity village) {
        return new PilgrimageRecommendationResponse(
                entity.getId(),
                entity.getRecommendedVillageId(),
                village != null ? village.getSlug() : null,
                village != null ? village.getName() : null,
                village != null ? village.getCategory() : null,
                village != null ? village.getIconR2Key() : null,
                entity.getRecommendedDate(),
                entity.getReason(),
                entity.getVisitedAt()
        );
    }
}
