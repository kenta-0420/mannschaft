package com.mannschaft.app.match.repository;

import com.mannschaft.app.match.entity.MatchAttachmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * 子 {@link MatchAttachmentEntity} のリポジトリ（<b>match_id スコープ専用</b>・01 §B.7 / §A.4）。
 *
 * <p><b>【IDOR 根絶・01 §A.4 / 03 §C.7a】</b> 本リポジトリは {@code AbstractTenantAwareRepository} を
 * 継承<b>しない</b>（子テーブルは organization_id を持たないため）。取得は必ず {@code match_id} スコープで行い、
 * 親 matches をテナント取得した後の二段アクセスに供する。{@code findById}（JpaRepository 由来）で取得した場合も
 * Service が {@code match_id} 一致を必ず検証する（子 ID 直引きで親をまたぐ越境を遮断）。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/01_domain_and_ddl.md §A.4 / §B.7</p>
 */
@Repository
public interface MatchAttachmentRepository extends JpaRepository<MatchAttachmentEntity, UUID> {

    /** 指定試合の添付を作成日時昇順で取得する。 */
    List<MatchAttachmentEntity> findByMatchIdOrderByCreatedAtAsc(UUID matchId);

    /** 指定試合の添付件数（件数上限チェック用）。 */
    long countByMatchId(UUID matchId);
}
