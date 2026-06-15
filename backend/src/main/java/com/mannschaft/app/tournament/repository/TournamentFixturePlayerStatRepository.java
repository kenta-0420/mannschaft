package com.mannschaft.app.tournament.repository;

import com.mannschaft.app.tournament.entity.TournamentFixturePlayerStatEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 個人成績リポジトリ。
 */
public interface TournamentFixturePlayerStatRepository extends JpaRepository<TournamentFixturePlayerStatEntity, Long> {

    List<TournamentFixturePlayerStatEntity> findByMatchId(Long matchId);

    List<TournamentFixturePlayerStatEntity> findByMatchIdAndUserId(Long matchId, Long userId);

    Optional<TournamentFixturePlayerStatEntity> findByMatchIdAndUserIdAndStatKey(
            Long matchId, Long userId, String statKey);

    @Query("SELECT ps FROM TournamentFixturePlayerStatEntity ps " +
           "JOIN TournamentFixtureEntity m ON ps.matchId = m.id " +
           "JOIN TournamentMatchdayEntity md ON m.matchdayId = md.id " +
           "JOIN TournamentDivisionEntity d ON md.divisionId = d.id " +
           "WHERE d.tournamentId = :tournamentId AND ps.statKey = :statKey AND m.status = 'COMPLETED'")
    List<TournamentFixturePlayerStatEntity> findByTournamentIdAndStatKey(
            @Param("tournamentId") Long tournamentId, @Param("statKey") String statKey);

    @Query("SELECT ps FROM TournamentFixturePlayerStatEntity ps " +
           "JOIN TournamentFixtureEntity m ON ps.matchId = m.id " +
           "JOIN TournamentMatchdayEntity md ON m.matchdayId = md.id " +
           "JOIN TournamentDivisionEntity d ON md.divisionId = d.id " +
           "WHERE d.tournamentId = :tournamentId AND m.status = 'COMPLETED'")
    List<TournamentFixturePlayerStatEntity> findByTournamentId(
            @Param("tournamentId") Long tournamentId);
}
