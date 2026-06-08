package com.mannschaft.app.match.repository;

import com.mannschaft.app.match.entity.PlayerAppearanceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * 子 {@link PlayerAppearanceEntity} のリポジトリ（<b>match_id スコープ専用</b>）。
 *
 * <p><b>【IDOR 根絶・01 §A.4】</b> 本リポジトリは {@code AbstractTenantAwareRepository} を継承<b>しない</b>
 * （子テーブルは organization_id を持たないため）。子 ID 直引きを業務用に生やさず、必ず
 * {@code match_id} スコープで取得する（親 matches をテナント取得した後の二段アクセス）。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/01_domain_and_ddl.md §A.1 / §A.4</p>
 */
@Repository
public interface PlayerAppearanceRepository extends JpaRepository<PlayerAppearanceEntity, UUID> {

    /** 指定試合の出場記録を取得する。 */
    List<PlayerAppearanceEntity> findByMatchId(UUID matchId);

    /** 指定試合のサイド別出場記録を取得する。 */
    List<PlayerAppearanceEntity> findByMatchIdAndTeamSide(
            UUID matchId, com.mannschaft.app.match.domain.TeamSide teamSide);

    /** 指定試合・指定登録選手の出場記録（自動算出 upsert キー）。 */
    java.util.Optional<PlayerAppearanceEntity> findByMatchIdAndPlayerUserId(UUID matchId, Long playerUserId);

    /** 指定試合の出場記録件数。 */
    long countByMatchId(UUID matchId);

    /** 指定試合の出場記録を一括削除する。 */
    void deleteByMatchId(UUID matchId);
}
