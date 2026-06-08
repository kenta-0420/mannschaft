package com.mannschaft.app.match.repository;

import com.mannschaft.app.match.entity.MatchEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * 子 {@link MatchEventEntity} のリポジトリ（<b>match_id スコープ専用</b>）。
 *
 * <p><b>【IDOR 根絶・01 §A.4】</b> 本リポジトリは {@code AbstractTenantAwareRepository} を継承<b>しない</b>
 * （子テーブルは organization_id を持たないため）。さらに、子 ID 直引き（{@code findById(eventId)} を
 * 業務用に使う経路）はテナントゲートを素通りし IDOR の温床になるため<b>生やさない</b>。
 * 取得は必ず {@code match_id} スコープで行い、親 matches をテナント取得した後の二段アクセスに供する。</p>
 *
 * <p>{@code JpaRepository} 由来の {@code findById} はフレームワーク上存在するが、業務コードでは
 * 子 ID 直引きを禁止し、必ず本クラスの match_id スコープメソッド経由で取得する（実装規約）。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/01_domain_and_ddl.md §A.1 / §A.4</p>
 */
@Repository
public interface MatchEventRepository extends JpaRepository<MatchEventEntity, UUID> {

    /** 指定試合のイベントを表示順（period→minute→sort_seq）で取得する。 */
    List<MatchEventEntity> findByMatchIdOrderByPeriodAscMinuteAscSortSeqAsc(UUID matchId);

    /** 指定試合のイベントを取得する（順序指定なし）。 */
    List<MatchEventEntity> findByMatchId(UUID matchId);

    /** 指定試合のイベント件数。 */
    long countByMatchId(UUID matchId);

    /** 指定試合のイベントを一括削除する（親の物理削除前の明示削除など）。 */
    void deleteByMatchId(UUID matchId);
}
