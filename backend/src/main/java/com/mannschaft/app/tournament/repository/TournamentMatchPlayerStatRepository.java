package com.mannschaft.app.tournament.repository;

import com.mannschaft.app.tournament.entity.TournamentMatchPlayerStatEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 個人成績リポジトリ。
 */
public interface TournamentMatchPlayerStatRepository extends JpaRepository<TournamentMatchPlayerStatEntity, Long> {

    List<TournamentMatchPlayerStatEntity> findByMatchId(Long matchId);

    List<TournamentMatchPlayerStatEntity> findByMatchIdAndUserId(Long matchId, Long userId);

    Optional<TournamentMatchPlayerStatEntity> findByMatchIdAndUserIdAndStatKey(
            Long matchId, Long userId, String statKey);

    @Query("SELECT ps FROM TournamentMatchPlayerStatEntity ps " +
           "JOIN TournamentMatchEntity m ON ps.matchId = m.id " +
           "JOIN TournamentMatchdayEntity md ON m.matchdayId = md.id " +
           "JOIN TournamentDivisionEntity d ON md.divisionId = d.id " +
           "WHERE d.tournamentId = :tournamentId AND ps.statKey = :statKey AND m.status = 'COMPLETED'")
    List<TournamentMatchPlayerStatEntity> findByTournamentIdAndStatKey(
            @Param("tournamentId") Long tournamentId, @Param("statKey") String statKey);

    @Query("SELECT ps FROM TournamentMatchPlayerStatEntity ps " +
           "JOIN TournamentMatchEntity m ON ps.matchId = m.id " +
           "JOIN TournamentMatchdayEntity md ON m.matchdayId = md.id " +
           "JOIN TournamentDivisionEntity d ON md.divisionId = d.id " +
           "WHERE d.tournamentId = :tournamentId AND m.status = 'COMPLETED'")
    List<TournamentMatchPlayerStatEntity> findByTournamentId(
            @Param("tournamentId") Long tournamentId);
}
