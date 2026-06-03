package com.mannschaft.app.tournament.submission;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 大会提出枠リポジトリ（F08.7.1/06）。
 *
 * <p>{@code organization_id} で絞り込めるが、本機能の主たる絞り込み軸は {@code tournament_id} のため、
 * {@code AbstractTenantAwareRepository} ではなく素の {@link JpaRepository} を継承し、
 * テナント絞り込みは {@code findByIdAndOrganizationId} で個別に行う（IDOR 対策）。</p>
 */
public interface TournamentSubmissionRequirementRepository
        extends JpaRepository<TournamentSubmissionRequirementEntity, UUID> {

    /** 大会単位の提出枠一覧（論理削除は {@code @SQLRestriction} で除外）。 */
    List<TournamentSubmissionRequirementEntity> findByTournamentIdOrderByCreatedAtAsc(Long tournamentId);

    /** ID + 主催組織でのスコープ付き取得（IDOR 対策・他組織の提出枠は 404 に倒す）。 */
    Optional<TournamentSubmissionRequirementEntity> findByIdAndOrganizationId(UUID id, Long organizationId);
}
