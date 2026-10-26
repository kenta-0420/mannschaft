package com.mannschaft.app.match.repository;

import com.mannschaft.app.match.entity.MatchScoreEntryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * 子 {@link MatchScoreEntryEntity} のリポジトリ（<b>match_id スコープ専用</b>・sports/07_scored.md §5B）。
 *
 * <p><b>【IDOR 根絶・01 §A.4】</b> 本リポジトリは {@code AbstractTenantAwareRepository} を継承<b>しない</b>
 * （子テーブルは organization_id を持たないため）。子 ID 直引き（{@code findById}）は業務用に使わず、
 * 取得は必ず {@code match_id} スコープで行い、親 matches をテナント取得した後の二段アクセスに供する。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/01_domain_and_ddl.md §A.4 / sports/07_scored.md §5B</p>
 */
@Repository
public interface MatchScoreEntryRepository extends JpaRepository<MatchScoreEntryEntity, UUID> {

    /** 指定試合の出場者エントリを順位昇順（同順位は合計点降順）で取得する（match_id スコープ・順位表向き）。 */
    List<MatchScoreEntryEntity> findByMatchIdOrderByRankPositionAscTotalScaledDesc(UUID matchId);

    /** 指定試合の出場者エントリ件数。 */
    long countByMatchId(UUID matchId);

    /** 指定試合の出場者エントリを一括削除する（再記録のための全置換）。 */
    void deleteByMatchId(UUID matchId);
}
