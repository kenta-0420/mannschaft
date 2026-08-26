package com.mannschaft.app.match.repository;

import com.mannschaft.app.match.entity.MatchScoredComponentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * 子 {@link MatchScoredComponentEntity} のリポジトリ（<b>match_id スコープ専用</b>・sports/07_scored.md §4B）。
 *
 * <p><b>【IDOR 根絶・01 §A.4】</b> 本リポジトリは {@code AbstractTenantAwareRepository} を継承<b>しない</b>
 * （子テーブルは organization_id を持たないため）。子 ID 直引き（{@code findById}）は業務用に使わず、
 * 取得は必ず {@code match_id} スコープで行い、親 matches をテナント取得した後の二段アクセスに供する。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/01_domain_and_ddl.md §A.4 / sports/07_scored.md §4B</p>
 */
@Repository
public interface MatchScoredComponentRepository extends JpaRepository<MatchScoredComponentEntity, UUID> {

    /** 指定試合の採点内訳を作成時刻昇順で取得する（match_id スコープ）。 */
    List<MatchScoredComponentEntity> findByMatchIdOrderByCreatedAtAsc(UUID matchId);

    /** 指定試合の採点内訳件数。 */
    long countByMatchId(UUID matchId);

    /** 指定試合の採点内訳を一括削除する（再記録のための置換など）。 */
    void deleteByMatchId(UUID matchId);
}
