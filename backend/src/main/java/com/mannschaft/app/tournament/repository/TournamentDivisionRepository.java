package com.mannschaft.app.tournament.repository;

import com.mannschaft.app.tournament.entity.TournamentDivisionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * ディビジョンリポジトリ。
 */
public interface TournamentDivisionRepository extends JpaRepository<TournamentDivisionEntity, Long> {

    List<TournamentDivisionEntity> findByTournamentIdOrderByLevelAscSortOrderAsc(Long tournamentId);

    Optional<TournamentDivisionEntity> findByIdAndTournamentId(Long id, Long tournamentId);
}
