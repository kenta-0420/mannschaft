package com.mannschaft.app.tournament.repository;

import com.mannschaft.app.tournament.entity.TournamentMatchdayEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 節リポジトリ。
 */
public interface TournamentMatchdayRepository extends JpaRepository<TournamentMatchdayEntity, Long> {

    List<TournamentMatchdayEntity> findByDivisionIdOrderByMatchdayNumberAsc(Long divisionId);

    Optional<TournamentMatchdayEntity> findByIdAndDivisionId(Long id, Long divisionId);

    Optional<TournamentMatchdayEntity> findTopByDivisionIdOrderByMatchdayNumberDesc(Long divisionId);
}
