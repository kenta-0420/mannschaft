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
 * @param displayName  指名されたユーザーの表示名（NameResolverService で解決。退会済み等は既定フォールバック）
 * @param createdBy    指名した主催組織 ADMIN の user_id
 * @param createdAt    指名日時
 */
public record ScorekeeperResponse(
        UUID id,
        Long tournamentId,
        Long userId,
        String displayName,
        Long createdBy,
        LocalDateTime createdAt
) {
    /**
     * エンティティと解決済み表示名からレスポンスを組み立てる。
     *
     * @param entity      指名エンティティ
     * @param displayName NameResolverService で解決した表示名（null 可）
     */
    public static ScorekeeperResponse of(TournamentScorekeeperEntity entity, String displayName) {
        return new ScorekeeperResponse(
                entity.getId(),
                entity.getTournamentId(),
                entity.getUserId(),
                displayName,
                entity.getCreatedBy(),
                entity.getCreatedAt()
        );
    }
}
