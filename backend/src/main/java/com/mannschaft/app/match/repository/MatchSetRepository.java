package com.mannschaft.app.match.repository;

import com.mannschaft.app.match.entity.MatchSetEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 子 {@link MatchSetEntity} のリポジトリ（<b>match_id スコープ専用</b>・01 §B.5）。
 *
 * <p><b>【IDOR 根絶・01 §A.4】</b> 本リポジトリは {@code AbstractTenantAwareRepository} を継承<b>しない</b>
 * （子テーブルは organization_id を持たないため）。子 ID 直引き（{@code findById}）は業務用に使わず、
 * 取得は必ず {@code match_id} スコープで行い、親 matches をテナント取得した後の二段アクセスに供する。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/01_domain_and_ddl.md §A.4 / §B.5</p>
 */
@Repository
public interface MatchSetRepository extends JpaRepository<MatchSetEntity, UUID> {

    /** 指定試合のセットをセット番号昇順で取得する。 */
    List<MatchSetEntity> findByMatchIdOrderBySetNumberAsc(UUID matchId);

    /** 指定試合・指定セット番号の行を取得する（upsert キー＝(match_id, set_number)）。 */
    Optional<MatchSetEntity> findByMatchIdAndSetNumber(UUID matchId, Integer setNumber);

    /** 指定試合のセット件数。 */
    long countByMatchId(UUID matchId);

    /** 指定試合のセットを一括削除する（親の物理削除前の明示削除など）。 */
    void deleteByMatchId(UUID matchId);
}
