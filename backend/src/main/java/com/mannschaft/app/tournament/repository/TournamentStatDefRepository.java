package com.mannschaft.app.tournament.repository;

import com.mannschaft.app.tournament.entity.TournamentStatDefEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 大会個人成績項目リポジトリ。
 */
public interface TournamentStatDefRepository extends JpaRepository<TournamentStatDefEntity, Long> {

    List<TournamentStatDefEntity> findByTournamentIdOrderBySortOrderAsc(Long tournamentId);

    List<TournamentStatDefEntity> findByTournamentIdAndIsRankingTargetTrueOrderBySortOrderAsc(Long tournamentId);

    Optional<TournamentStatDefEntity> findByTournamentIdAndStatKey(Long tournamentId, String statKey);

    void deleteByTournamentId(Long tournamentId);
}
