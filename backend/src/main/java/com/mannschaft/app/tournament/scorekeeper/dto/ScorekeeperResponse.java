package com.mannschaft.app.tournament.scorekeeper.dto;

import com.mannschaft.app.tournament.scorekeeper.TournamentScorekeeperEntity;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 大会スコアキーパー指名レスポンス DTO（F08.7 項目③）。
 *
 * @param id           指名 ID（UUIDv7）
 * @param tournamentId 対象大会 ID
 * @param userId       スコアキーパーに指名されたユーザー ID
 * @param createdBy    指名した主催組織 ADMIN の user_id
 * @param createdAt    指名日時
 */
public record ScorekeeperResponse(
        UUID id,
        Long tournamentId,
        Long userId,
        Long createdBy,
        LocalDateTime createdAt
) {
    /**
     * エンティティからレスポンスを組み立てる。
     */
    public static ScorekeeperResponse of(TournamentScorekeeperEntity entity) {
        return new ScorekeeperResponse(
                entity.getId(),
                entity.getTournamentId(),
                entity.getUserId(),
                entity.getCreatedBy(),
                entity.getCreatedAt()
        );
    }
}
