package com.mannschaft.app.tournament.fee;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 大会参加費リポジトリ（F08.7.1/07）。
 *
 * <p>{@code organization_id} で絞り込めるが、本機能の主たる絞り込み軸は {@code tournament_id} のため、
 * {@code AbstractTenantAwareRepository} ではなく素の {@link JpaRepository} を継承し、
 * テナント絞り込みは {@code findByIdAndOrganizationId} で個別に行う（IDOR 対策）。</p>
 */
public interface TournamentFeeRepository extends JpaRepository<TournamentFeeEntity, UUID> {

    /** 大会単位の参加費一覧（論理削除は {@code @SQLRestriction} で除外）。 */
    List<TournamentFeeEntity> findByTournamentIdOrderByCreatedAtAsc(Long tournamentId);

    /** ID + 主催組織でのスコープ付き取得（IDOR 対策・他組織の fee は 404 に倒す）。 */
    Optional<TournamentFeeEntity> findByIdAndOrganizationId(UUID id, Long organizationId);

    /** 主催組織の参加費一覧（認証ユーザーの所属組織で絞り込む際に使用）。 */
    List<TournamentFeeEntity> findByOrganizationId(Long organizationId);
}
