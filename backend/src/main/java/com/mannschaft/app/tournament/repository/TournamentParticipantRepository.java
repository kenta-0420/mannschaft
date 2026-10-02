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
     * 認可根治戦役 Wave2 トランシェ2C: 参加チームが指定ディビジョン配下に属するかの束縛検証（BOLA 対策）。
     * pId を divisionId で絞り込み、他ディビジョンの参加チームを URL 差し替えで操作できないようにする。
     */
    Optional<TournamentParticipantEntity> findByIdAndDivisionId(Long id, Long divisionId);

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

    /**
     * F08.7.1/06 提出状況ダッシュボード: 大会の参加チーム（REGISTERED/ACTIVE）の team_id を重複排除で列挙する。
     *
     * <p>提出枠 {@code target_scope=ALL_TEAMS} の「対象チーム母集団」を解決するために使う。
     * クロスドメインは ID 参照の JOIN のみ（原則1）。</p>
     *
     * @param tournamentId 大会 ID
     * @return 参加チーム ID（昇順・重複排除）
     */
    @Query("""
            SELECT DISTINCT p.teamId FROM TournamentParticipantEntity p
            JOIN TournamentDivisionEntity d ON p.divisionId = d.id
            WHERE d.tournamentId = :tournamentId
              AND p.status IN (com.mannschaft.app.tournament.ParticipantStatus.REGISTERED,
                               com.mannschaft.app.tournament.ParticipantStatus.ACTIVE)
            ORDER BY p.teamId ASC
            """)
    List<Long> findDistinctParticipantTeamIdsByTournamentId(@Param("tournamentId") Long tournamentId);

    /**
     * F08.7.1/06 提出状況ダッシュボード: ディビジョンの参加チーム（REGISTERED/ACTIVE）の team_id を列挙する。
     *
     * @param divisionId ディビジョン ID
     * @return 参加チーム ID（昇順・重複排除）
     */
    @Query("""
            SELECT DISTINCT p.teamId FROM TournamentParticipantEntity p
            WHERE p.divisionId = :divisionId
              AND p.status IN (com.mannschaft.app.tournament.ParticipantStatus.REGISTERED,
                               com.mannschaft.app.tournament.ParticipantStatus.ACTIVE)
            ORDER BY p.teamId ASC
            """)
    List<Long> findDistinctParticipantTeamIdsByDivisionId(@Param("divisionId") Long divisionId);

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
     *
     * <p>注1: {@code tournament_divisions} テーブルには {@code deleted_at} カラムが存在しないため
     * （設計書 §3 テーブル一覧・論理削除なし）、{@code d.deleted_at IS NULL} 条件は使用しない。</p>
     * <p>注2: MySQL 8 の nativeQuery で {@code boolean} 型を返すと JDBC が BIGINT/TINYINT をキャストできず
     * {@link ClassCastException} が発生するため {@code int} で受け取り呼び出し元でゼロ比較する。</p>
     */
    @Query(value = "SELECT COUNT(*) FROM tournament_participants p " +
            "JOIN tournament_divisions d ON p.division_id = d.id " +
            "JOIN memberships m ON m.scope_type = 'TEAM' AND m.scope_id = p.team_id " +
            "WHERE d.tournament_id = :tournamentId " +
            "  AND p.status IN ('REGISTERED', 'ACTIVE') " +
            "  AND m.user_id = :userId AND m.left_at IS NULL " +
            "LIMIT 1",
            nativeQuery = true)
    int countActiveMemberOfAnyParticipantTeam(@Param("tournamentId") Long tournamentId,
                                              @Param("userId") Long userId);

    /**
     * @deprecated existsActiveMemberOfAnyParticipantTeam は MySQL 8 の nativeQuery で boolean 変換時に
     *             ClassCastException が発生する。{@link #countActiveMemberOfAnyParticipantTeam} を使うこと。
     */
    @Deprecated
    default boolean existsActiveMemberOfAnyParticipantTeam(Long tournamentId, Long userId) {
        return countActiveMemberOfAnyParticipantTeam(tournamentId, userId) > 0;
    }

    /**
     * 大会のいずれかの参加チーム（REGISTERED/ACTIVE）で当該ユーザーが ADMIN/DEPUTY_ADMIN か。
     *
     * <p>{@code tournament_participants × tournament_divisions × user_roles × roles} を 1 クエリで JOIN する。</p>
     *
     * <p>注1: {@code tournament_divisions} テーブルには {@code deleted_at} カラムが存在しないため
     * （設計書 §3 テーブル一覧・論理削除なし）、{@code d.deleted_at IS NULL} 条件は使用しない。</p>
     * <p>注2: MySQL 8 nativeQuery で boolean を返すと ClassCastException が発生する（TOUR-006 根治）。
     * {@code int} で受け取り呼び出し元でゼロ比較する。</p>
     */
    @Query(value = "SELECT COUNT(*) FROM tournament_participants p " +
            "JOIN tournament_divisions d ON p.division_id = d.id " +
            "JOIN user_roles ur ON ur.team_id = p.team_id AND ur.user_id = :userId " +
            "JOIN roles r ON r.id = ur.role_id " +
            "WHERE d.tournament_id = :tournamentId " +
            "  AND p.status IN ('REGISTERED', 'ACTIVE') " +
            "  AND r.name IN ('ADMIN', 'DEPUTY_ADMIN') " +
            "LIMIT 1",
            nativeQuery = true)
    int countTeamAdminOfAnyParticipantTeam(@Param("tournamentId") Long tournamentId,
                                           @Param("userId") Long userId);

    /**
     * @deprecated existsTeamAdminOfAnyParticipantTeam は MySQL 8 の nativeQuery で boolean 変換時に
     *             ClassCastException が発生する。{@link #countTeamAdminOfAnyParticipantTeam} を使うこと。
     */
    @Deprecated
    default boolean existsTeamAdminOfAnyParticipantTeam(Long tournamentId, Long userId) {
        return countTeamAdminOfAnyParticipantTeam(tournamentId, userId) > 0;
    }

    /**
     * ディビジョンの参加チーム（REGISTERED/ACTIVE）に当該ユーザーがアクティブメンバーとして所属するか。
     *
     * <p>注: MySQL 8 nativeQuery で boolean を返すと ClassCastException が発生するため int で受け取る。</p>
     */
    @Query(value = "SELECT COUNT(*) FROM tournament_participants p " +
            "JOIN memberships m ON m.scope_type = 'TEAM' AND m.scope_id = p.team_id " +
            "WHERE p.division_id = :divisionId " +
            "  AND p.status IN ('REGISTERED', 'ACTIVE') " +
            "  AND m.user_id = :userId AND m.left_at IS NULL " +
            "LIMIT 1",
            nativeQuery = true)
    int countActiveMemberOfDivisionParticipantTeam(@Param("divisionId") Long divisionId,
                                                   @Param("userId") Long userId);

    /**
     * @deprecated existsActiveMemberOfDivisionParticipantTeam は ClassCastException が発生する。
     *             {@link #countActiveMemberOfDivisionParticipantTeam} を使うこと。
     */
    @Deprecated
    default boolean existsActiveMemberOfDivisionParticipantTeam(Long divisionId, Long userId) {
        return countActiveMemberOfDivisionParticipantTeam(divisionId, userId) > 0;
    }

    /**
     * ディビジョンの参加チーム（REGISTERED/ACTIVE）で当該ユーザーが ADMIN/DEPUTY_ADMIN か。
     *
     * <p>注: MySQL 8 nativeQuery で boolean を返すと ClassCastException が発生するため int で受け取る。</p>
     */
    @Query(value = "SELECT COUNT(*) FROM tournament_participants p " +
            "JOIN user_roles ur ON ur.team_id = p.team_id AND ur.user_id = :userId " +
            "JOIN roles r ON r.id = ur.role_id " +
            "WHERE p.division_id = :divisionId " +
            "  AND p.status IN ('REGISTERED', 'ACTIVE') " +
            "  AND r.name IN ('ADMIN', 'DEPUTY_ADMIN') " +
            "LIMIT 1",
            nativeQuery = true)
    int countTeamAdminOfDivisionParticipantTeam(@Param("divisionId") Long divisionId,
                                                @Param("userId") Long userId);

    /**
     * @deprecated existsTeamAdminOfDivisionParticipantTeam は ClassCastException が発生する。
     *             {@link #countTeamAdminOfDivisionParticipantTeam} を使うこと。
     */
    @Deprecated
    default boolean existsTeamAdminOfDivisionParticipantTeam(Long divisionId, Long userId) {
        return countTeamAdminOfDivisionParticipantTeam(divisionId, userId) > 0;
    }
}
