package com.mannschaft.app.tournament.repository;

import com.mannschaft.app.tournament.entity.TournamentPromotionRecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 昇降格記録リポジトリ。
 */
public interface TournamentPromotionRecordRepository extends JpaRepository<TournamentPromotionRecordEntity, Long> {

    List<TournamentPromotionRecordEntity> findByTournamentIdOrderByExecutedAtDesc(Long tournamentId);

    Optional<TournamentPromotionRecordEntity> findByTournamentIdAndTeamId(Long tournamentId, Long teamId);

    List<TournamentPromotionRecordEntity> findByTeamIdOrderByExecutedAtDesc(Long teamId);
}
