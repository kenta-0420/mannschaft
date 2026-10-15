package com.mannschaft.app.tournament.repository;

import com.mannschaft.app.tournament.entity.TournamentTiebreakerEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 大会タイブレークリポジトリ。
 */
public interface TournamentTiebreakerRepository extends JpaRepository<TournamentTiebreakerEntity, Long> {

    List<TournamentTiebreakerEntity> findByTournamentIdOrderByPriorityAsc(Long tournamentId);

    void deleteByTournamentId(Long tournamentId);
}
