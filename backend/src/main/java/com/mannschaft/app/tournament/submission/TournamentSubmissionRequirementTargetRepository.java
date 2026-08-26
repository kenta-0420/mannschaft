package com.mannschaft.app.tournament.submission;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * 大会提出枠の対象チーム明細リポジトリ（F08.7.1/06）。
 */
public interface TournamentSubmissionRequirementTargetRepository
        extends JpaRepository<TournamentSubmissionRequirementTargetEntity, UUID> {

    List<TournamentSubmissionRequirementTargetEntity> findByRequirementId(UUID requirementId);

    boolean existsByRequirementIdAndTeamId(UUID requirementId, Long teamId);

    void deleteByRequirementId(UUID requirementId);
}
