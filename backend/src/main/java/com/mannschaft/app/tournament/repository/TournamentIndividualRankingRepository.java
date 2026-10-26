package com.mannschaft.app.tournament.repository;

import com.mannschaft.app.tournament.entity.TournamentIndividualRankingEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 個人ランキングリポジトリ。
 */
public interface TournamentIndividualRankingRepository extends JpaRepository<TournamentIndividualRankingEntity, Long> {

    Page<TournamentIndividualRankingEntity> findByTournamentIdAndStatKeyOrderByRankAsc(
            Long tournamentId, String statKey, Pageable pageable);

    List<TournamentIndividualRankingEntity> findByTournamentIdAndStatKeyOrderByRankAsc(
            Long tournamentId, String statKey);

    Optional<TournamentIndividualRankingEntity> findByTournamentIdAndStatKeyAndUserId(
            Long tournamentId, String statKey, Long userId);

    void deleteByTournamentIdAndStatKey(Long tournamentId, String statKey);

    void deleteByTournamentId(Long tournamentId);
}
