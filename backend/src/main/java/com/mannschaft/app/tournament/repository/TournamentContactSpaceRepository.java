package com.mannschaft.app.tournament.repository;

import com.mannschaft.app.tournament.ContactSpaceKind;
import com.mannschaft.app.tournament.ContactSpaceScopeType;
import com.mannschaft.app.tournament.entity.TournamentContactSpaceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 大会・ディビジョン連絡スペースリポジトリ（F08.7.1 連絡機能）。
 *
 * <p>{@code tournament_contact_space} はテナント単位で爆発的に増えるテーブルではなく、
 * 大会 ID から組織を辿れるため {@code AbstractTenantAwareRepository} は適用しない（設計書 §2.1 備考）。</p>
 */
public interface TournamentContactSpaceRepository extends JpaRepository<TournamentContactSpaceEntity, UUID> {

    /**
     * スコープ×種別で連絡スペースを 1 件取得する（冪等化・逆引き）。論理削除済みは除外（{@code @SQLRestriction}）。
     */
    Optional<TournamentContactSpaceEntity> findByScopeTypeAndScopeIdAndSpaceKind(
            ContactSpaceScopeType scopeType, Long scopeId, ContactSpaceKind spaceKind);

    /**
     * スコープに紐づく全スペース（掲示板・チャット）を取得する。
     */
    List<TournamentContactSpaceEntity> findByScopeTypeAndScopeId(
            ContactSpaceScopeType scopeType, Long scopeId);

    /**
     * 払い出し先実体（chat_channel id / bulletin category id）から逆引きする。
     */
    Optional<TournamentContactSpaceEntity> findBySpaceKindAndRefId(
            ContactSpaceKind spaceKind, Long refId);

    /**
     * 大会全体スペースと、指定部門群のスペースを 1 本で取得する（CMP-261118-0900 §10.3.5 (c)）。
     * 論理削除済みは {@code @SQLRestriction} で除外される。
     */
    @Query("SELECT s FROM TournamentContactSpaceEntity s "
            + "WHERE (s.scopeType = com.mannschaft.app.tournament.ContactSpaceScopeType.TOURNAMENT AND s.scopeId = :tournamentId) "
            + "   OR (s.scopeType = com.mannschaft.app.tournament.ContactSpaceScopeType.TOURNAMENT_DIVISION "
            + "       AND s.scopeId IN :divisionIds)")
    List<TournamentContactSpaceEntity> findForTournamentAndDivisions(
            @Param("tournamentId") Long tournamentId,
            @Param("divisionIds") Collection<Long> divisionIds);
}
