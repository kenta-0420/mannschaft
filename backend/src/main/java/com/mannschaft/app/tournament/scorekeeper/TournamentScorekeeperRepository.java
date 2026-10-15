package com.mannschaft.app.tournament.scorekeeper;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 大会スコアキーパー指名リポジトリ（F08.7 順位UI 項目③）。
 *
 * <p>主たる絞り込み軸は {@code tournament_id} のため、{@code AbstractTenantAwareRepository}
 * （{@code organization_id} 軸）ではなく素の {@link JpaRepository} を継承する。テナント帰属の検証は
 * 呼び出し側で「大会が主催組織に属するか」を確認したうえで本リポジトリを使う。</p>
 */
public interface TournamentScorekeeperRepository
        extends JpaRepository<TournamentScorekeeperEntity, UUID> {

    /** 大会の指名スコアキーパー一覧（指名順）。 */
    List<TournamentScorekeeperEntity> findByTournamentIdOrderByCreatedAtAsc(Long tournamentId);

    /** 当該ユーザーが当該大会のスコアキーパーに指名されているか（canEnterScore 条件②）。 */
    boolean existsByTournamentIdAndUserId(Long tournamentId, Long userId);

    /** 大会内の指名を ID で取得（IDOR 対策・他大会の指名は解決させない）。 */
    Optional<TournamentScorekeeperEntity> findByIdAndTournamentId(UUID id, Long tournamentId);

    /** 大会内の特定ユーザーの指名を取得（user_id 指定での解除に使う）。 */
    Optional<TournamentScorekeeperEntity> findByTournamentIdAndUserId(Long tournamentId, Long userId);
}
