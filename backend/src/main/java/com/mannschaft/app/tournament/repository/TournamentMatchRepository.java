package com.mannschaft.app.tournament.repository;

import com.mannschaft.app.tournament.MatchStatus;
import com.mannschaft.app.tournament.entity.TournamentMatchEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 対戦カードリポジトリ。
 */
public interface TournamentMatchRepository extends JpaRepository<TournamentMatchEntity, Long> {

    List<TournamentMatchEntity> findByMatchdayIdOrderByMatchNumberAsc(Long matchdayId);

    @Query("SELECT m FROM TournamentMatchEntity m " +
           "JOIN TournamentMatchdayEntity md ON m.matchdayId = md.id " +
           "WHERE md.divisionId = :divisionId AND m.status = :status")
    List<TournamentMatchEntity> findByDivisionIdAndStatus(
            @Param("divisionId") Long divisionId, @Param("status") MatchStatus status);

    @Query("SELECT m FROM TournamentMatchEntity m " +
           "JOIN TournamentMatchdayEntity md ON m.matchdayId = md.id " +
           "WHERE md.divisionId = :divisionId")
    List<TournamentMatchEntity> findByDivisionId(@Param("divisionId") Long divisionId);

    @Query("SELECT m FROM TournamentMatchEntity m " +
           "WHERE m.homeParticipantId = :participantId OR m.awayParticipantId = :participantId")
    List<TournamentMatchEntity> findByParticipantId(@Param("participantId") Long participantId);

    @Query("SELECT m FROM TournamentMatchEntity m " +
           "JOIN TournamentMatchdayEntity md ON m.matchdayId = md.id " +
           "JOIN TournamentDivisionEntity d ON md.divisionId = d.id " +
           "WHERE d.tournamentId = :tournamentId")
    List<TournamentMatchEntity> findByTournamentId(@Param("tournamentId") Long tournamentId);

    @Query("SELECT COUNT(m) FROM TournamentMatchEntity m " +
           "JOIN TournamentMatchdayEntity md ON m.matchdayId = md.id " +
           "WHERE md.divisionId = :divisionId AND m.status != 'COMPLETED' AND m.status != 'CANCELLED'")
    long countIncompleteByDivisionId(@Param("divisionId") Long divisionId);
}
