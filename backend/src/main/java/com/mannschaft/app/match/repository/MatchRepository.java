package com.mannschaft.app.match.repository;

import com.mannschaft.app.common.repository.AbstractTenantAwareRepository;
import com.mannschaft.app.match.entity.MatchEntity;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * 親 {@link MatchEntity} のリポジトリ。
 *
 * <p>テナント（organization_id）スコープを {@link AbstractTenantAwareRepository} で強制する（原則7）。
 * 子テーブル（match_events / player_appearances）への二段アクセスでは、
 * まず本リポジトリの {@code findByIdAndOrganizationIdAndDeletedAtIsNull} で
 * 親をテナント取得することが 1 段目のテナントゲートとなる（01 §A.4）。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/01_domain_and_ddl.md §A.1 / §A.4</p>
 */
@Repository
public interface MatchRepository extends AbstractTenantAwareRepository<MatchEntity, UUID> {
}
