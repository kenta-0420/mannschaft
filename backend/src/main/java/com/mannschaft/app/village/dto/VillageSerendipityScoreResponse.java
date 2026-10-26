package com.mannschaft.app.village.dto;

import com.mannschaft.app.village.entity.VillageSerendipityScoreEntity;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * F17.1 Phase 3-β — ご縁スコア応答 DTO。
 *
 * <p>フロント型 {@code VillageSerendipityScoreResponse} (frontend/app/types/village.ts) と
 * フィールド名を一致させる。スコアは 0.0〜1.0 の正規化値で返却する
 * （生の {@code interactionScore} は 100 で頭打ちにして割る）。</p>
 *
 * @param villageId       村 ID
 * @param userId          ユーザー ID
 * @param score           正規化スコア 0.0〜1.0（{@code interactionScore} / 100, max 1.0）
 * @param encounterCount  出会い回数（参考値、フロント表示で利用可）
 * @param rank            ランキング順位（1 始まり、未算出時は null）
 * @param lastComputedAt  最終更新日時（バッチ実行時刻）
 */
public record VillageSerendipityScoreResponse(
        UUID villageId,
        Long userId,
        double score,
        Long encounterCount,
        Integer rank,
        LocalDateTime lastComputedAt
) {

    /**
     * 正規化に用いる係数。{@code interactionScore} がこの値以上なら {@code score = 1.0}。
     * Phase 4 でテナント / 村サイズ別に動的調整する余地を残す（現状は固定 100）。
     */
    public static final double NORMALIZATION_DIVISOR = 100.0;

    /**
     * Entity → Response 変換。
     *
     * @param entity ご縁スコア Entity
     * @param rank   ランキング順位（null 可）
     */
    public static VillageSerendipityScoreResponse of(VillageSerendipityScoreEntity entity, Integer rank) {
        double normalized = Math.min(1.0, entity.getInteractionScore() / NORMALIZATION_DIVISOR);
        return new VillageSerendipityScoreResponse(
                entity.getVillageId(),
                entity.getUserId(),
                normalized,
                entity.getEncounterCount(),
                rank,
                entity.getLastUpdatedAt()
        );
    }
}
