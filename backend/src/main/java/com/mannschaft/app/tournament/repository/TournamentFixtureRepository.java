package com.mannschaft.app.tournament.repository;

import com.mannschaft.app.tournament.FixtureStatus;
import com.mannschaft.app.tournament.entity.TournamentFixtureEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 対戦カードリポジトリ。
 */
public interface TournamentFixtureRepository extends JpaRepository<TournamentFixtureEntity, Long> {

    List<TournamentFixtureEntity> findByMatchdayIdOrderByMatchNumberAsc(Long matchdayId);

    @Query("SELECT m FROM TournamentFixtureEntity m " +
           "JOIN TournamentMatchdayEntity md ON m.matchdayId = md.id " +
           "WHERE md.divisionId = :divisionId AND m.status = :status")
    List<TournamentFixtureEntity> findByDivisionIdAndStatus(
            @Param("divisionId") Long divisionId, @Param("status") FixtureStatus status);

    @Query("SELECT m FROM TournamentFixtureEntity m " +
           "JOIN TournamentMatchdayEntity md ON m.matchdayId = md.id " +
           "WHERE md.divisionId = :divisionId")
    List<TournamentFixtureEntity> findByDivisionId(@Param("divisionId") Long divisionId);

    @Query("SELECT m FROM TournamentFixtureEntity m " +
           "WHERE m.homeParticipantId = :participantId OR m.awayParticipantId = :participantId")
    List<TournamentFixtureEntity> findByParticipantId(@Param("participantId") Long participantId);

    @Query("SELECT m FROM TournamentFixtureEntity m " +
           "JOIN TournamentMatchdayEntity md ON m.matchdayId = md.id " +
           "JOIN TournamentDivisionEntity d ON md.divisionId = d.id " +
           "WHERE d.tournamentId = :tournamentId")
    List<TournamentFixtureEntity> findByTournamentId(@Param("tournamentId") Long tournamentId);

    @Query("SELECT COUNT(m) FROM TournamentFixtureEntity m " +
           "JOIN TournamentMatchdayEntity md ON m.matchdayId = md.id " +
           "WHERE md.divisionId = :divisionId AND m.status != 'COMPLETED' AND m.status != 'CANCELLED'")
    long countIncompleteByDivisionId(@Param("divisionId") Long divisionId);

    /**
     * 指定 match が指定 tournament 配下に属するか判定する（F08.7 項目③・IDOR 対策）。
     *
     * <p>{@code match → matchday → division → tournament} を JOIN し、一致件数を返す。
     * スコア入力認可で「path の tId と matchId が同一大会か」を検証する用途。</p>
     */
    @Query("SELECT COUNT(m) FROM TournamentFixtureEntity m " +
           "JOIN TournamentMatchdayEntity md ON m.matchdayId = md.id " +
           "JOIN TournamentDivisionEntity d ON md.divisionId = d.id " +
           "WHERE m.id = :matchId AND d.tournamentId = :tournamentId")
    long countByIdAndTournamentId(@Param("matchId") Long matchId, @Param("tournamentId") Long tournamentId);
}
