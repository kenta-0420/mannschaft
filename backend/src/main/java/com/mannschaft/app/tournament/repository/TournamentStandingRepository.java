package com.mannschaft.app.tournament.repository;

import com.mannschaft.app.tournament.entity.TournamentStandingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 順位表リポジトリ。
 */
public interface TournamentStandingRepository extends JpaRepository<TournamentStandingEntity, Long> {

    List<TournamentStandingEntity> findByDivisionIdOrderByRankAsc(Long divisionId);

    Optional<TournamentStandingEntity> findByDivisionIdAndParticipantId(Long divisionId, Long participantId);

    void deleteByDivisionId(Long divisionId);
}
