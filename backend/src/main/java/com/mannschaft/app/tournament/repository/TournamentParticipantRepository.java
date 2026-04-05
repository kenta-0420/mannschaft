package com.mannschaft.app.tournament.repository;

import com.mannschaft.app.tournament.ParticipantStatus;
import com.mannschaft.app.tournament.entity.TournamentParticipantEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    @Query("SELECT p FROM TournamentParticipantEntity p " +
           "JOIN TournamentDivisionEntity d ON p.divisionId = d.id " +
           "WHERE d.tournamentId = :tournamentId AND p.teamId = :teamId")
    List<TournamentParticipantEntity> findByTournamentIdAndTeamId(
            @Param("tournamentId") Long tournamentId, @Param("teamId") Long teamId);
}
