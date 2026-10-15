package com.mannschaft.app.repairplan.repository;

import com.mannschaft.app.common.repository.AbstractTenantAwareRepository;
import com.mannschaft.app.repairplan.entity.RepairQuoteKanban;

import java.util.List;
import java.util.UUID;

/**
 * 相見積もりカンバンリポジトリ。
 */
public interface RepairQuoteKanbanRepository extends AbstractTenantAwareRepository<RepairQuoteKanban, UUID> {

    /** スコープ × ステータス単位のカンバン取得。 */
    List<RepairQuoteKanban> findByScopeTypeAndScopeIdAndStatusAndDeletedAtIsNull(
            String scopeType, Long scopeId, String status);

    /** スコープ単位のカンバン全件（最新順）。 */
    List<RepairQuoteKanban> findByScopeTypeAndScopeIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            String scopeType, Long scopeId);

    /** F09.13 work_package からの逆引き。 */
    List<RepairQuoteKanban> findByWorkPackageIdAndDeletedAtIsNull(Long workPackageId);
}
