package com.mannschaft.app.tournament.repository;

import com.mannschaft.app.tournament.ParticipantStatus;
import com.mannschaft.app.tournament.dto.DivisionParticipantCountProjection;
import com.mannschaft.app.tournament.entity.TournamentDivisionEntity;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import com.mannschaft.app.tournament.entity.TournamentParticipantEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 参加チームリポジトリ。
 */
public interface TournamentParticipantRepository extends JpaRepository<TournamentParticipantEntity, Long> {

    List<TournamentParticipantEntity> findByDivisionIdOrderBySeedAsc(Long divisionId);

    List<TournamentParticipantEntity> findByDivisionIdAndStatus(Long divisionId, ParticipantStatus status);

    Optional<TournamentParticipantEntity> findByDivisionIdAndTeamId(Long divisionId, Long teamId);

    long countByDivisionId(Long divisionId);

    /**
     * F08.7.1 主催大会サマリ: 複数ディビジョンの参加チーム数を 1 クエリで一括集約する（N+1 回避）。
     *
     * <p>{@code GROUP BY division_id COUNT(*)}。参加レコードが 0 件のディビジョンは結果に含まれない
     * （呼び出し側で 0 件補完すること）。</p>
     *
     * @param divisionIds ディビジョン ID 集合（空の場合は空 List）
     * @return ディビジョン別参加数の射影リスト
     */
    @Query("""
            SELECT new com.mannschaft.app.tournament.dto.DivisionParticipantCountProjection(
                p.divisionId, COUNT(p.id))
            FROM TournamentParticipantEntity p
            WHERE p.divisionId IN :divisionIds
            GROUP BY p.divisionId
            """)
    List<DivisionParticipantCountProjection> countParticipantsByDivisionIdIn(
            @Param("divisionIds") Collection<Long> divisionIds);

    @Query("SELECT p FROM TournamentParticipantEntity p " +
           "JOIN TournamentDivisionEntity d ON p.divisionId = d.id " +
           "WHERE d.tournamentId = :tournamentId AND p.teamId = :teamId")
    List<TournamentParticipantEntity> findByTournamentIdAndTeamId(
            @Param("tournamentId") Long tournamentId, @Param("teamId") Long teamId);

    @Query("SELECT p FROM TournamentParticipantEntity p " +
           "JOIN TournamentDivisionEntity d ON p.divisionId = d.id " +
           "JOIN TournamentEntity t ON d.tournamentId = t.id " +
           "WHERE p.teamId = :teamId " +
           "ORDER BY t.createdAt DESC")
    List<TournamentParticipantEntity> findAllByTeamId(@Param("teamId") Long teamId);

    // ========================================================================
    // F08.7.1 連絡機能: 認可 N+1 回避用 exists クエリ（設計書 §4.3）
    //
    // 大会/ディビジョンの「いずれかの参加チーム」にユーザーがメンバー or 代表として
    // 所属するかを 1 SQL で判定する。参加チーム解決は tournament_participants を源泉とし、
    // 連絡可能ステータス（REGISTERED/ACTIVE）に絞る（WITHDRAWN/DISQUALIFIED を除外）。
    // クロスドメインは ID 参照の JOIN のみ（原則1）。
    // ========================================================================

    /**
     * 大会のいずれかの参加チーム（REGISTERED/ACTIVE）に当該ユーザーがアクティブメンバーとして所属するか。
     *
     * <p>{@code tournament_participants × tournament_divisions × memberships}（scope_type=TEAM・left_at IS NULL）
     * を 1 クエリで JOIN する。</p>
     */
    @Query(value = "SELECT COUNT(*) > 0 FROM tournament_participants p " +
            "JOIN tournament_divisions d ON p.division_id = d.id " +
            "JOIN memberships m ON m.scope_type = 'TEAM' AND m.scope_id = p.team_id " +
            "WHERE d.tournament_id = :tournamentId " +
            "  AND d.deleted_at IS NULL " +
            "  AND p.status IN ('REGISTERED', 'ACTIVE') " +
            "  AND m.user_id = :userId AND m.left_at IS NULL",
            nativeQuery = true)
    boolean existsActiveMemberOfAnyParticipantTeam(@Param("tournamentId") Long tournamentId,
                                                   @Param("userId") Long userId);

    /**
     * 大会のいずれかの参加チーム（REGISTERED/ACTIVE）で当該ユーザーが ADMIN/DEPUTY_ADMIN か。
     *
     * <p>{@code tournament_participants × tournament_divisions × user_roles × roles} を 1 クエリで JOIN する。</p>
     */
    @Query(value = "SELECT COUNT(*) > 0 FROM tournament_participants p " +
            "JOIN tournament_divisions d ON p.division_id = d.id " +
            "JOIN user_roles ur ON ur.team_id = p.team_id AND ur.user_id = :userId " +
            "JOIN roles r ON r.id = ur.role_id " +
            "WHERE d.tournament_id = :tournamentId " +
            "  AND d.deleted_at IS NULL " +
            "  AND p.status IN ('REGISTERED', 'ACTIVE') " +
            "  AND r.name IN ('ADMIN', 'DEPUTY_ADMIN')",
            nativeQuery = true)
    boolean existsTeamAdminOfAnyParticipantTeam(@Param("tournamentId") Long tournamentId,
                                                @Param("userId") Long userId);

    /**
     * ディビジョンの参加チーム（REGISTERED/ACTIVE）に当該ユーザーがアクティブメンバーとして所属するか。
     */
    @Query(value = "SELECT COUNT(*) > 0 FROM tournament_participants p " +
            "JOIN memberships m ON m.scope_type = 'TEAM' AND m.scope_id = p.team_id " +
            "WHERE p.division_id = :divisionId " +
            "  AND p.status IN ('REGISTERED', 'ACTIVE') " +
            "  AND m.user_id = :userId AND m.left_at IS NULL",
            nativeQuery = true)
    boolean existsActiveMemberOfDivisionParticipantTeam(@Param("divisionId") Long divisionId,
                                                        @Param("userId") Long userId);

    /**
     * ディビジョンの参加チーム（REGISTERED/ACTIVE）で当該ユーザーが ADMIN/DEPUTY_ADMIN か。
     */
    @Query(value = "SELECT COUNT(*) > 0 FROM tournament_participants p " +
            "JOIN user_roles ur ON ur.team_id = p.team_id AND ur.user_id = :userId " +
            "JOIN roles r ON r.id = ur.role_id " +
            "WHERE p.division_id = :divisionId " +
            "  AND p.status IN ('REGISTERED', 'ACTIVE') " +
            "  AND r.name IN ('ADMIN', 'DEPUTY_ADMIN')",
            nativeQuery = true)
    boolean existsTeamAdminOfDivisionParticipantTeam(@Param("divisionId") Long divisionId,
                                                     @Param("userId") Long userId);
}
