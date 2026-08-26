package com.mannschaft.app.tournament.fee;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * 大会参加費の対象チーム明細リポジトリ（F08.7.1/07）。
 */
public interface TournamentFeeTargetRepository extends JpaRepository<TournamentFeeTargetEntity, UUID> {

    List<TournamentFeeTargetEntity> findByFeeId(UUID feeId);

    boolean existsByFeeIdAndTeamId(UUID feeId, Long teamId);

    void deleteByFeeId(UUID feeId);
}
