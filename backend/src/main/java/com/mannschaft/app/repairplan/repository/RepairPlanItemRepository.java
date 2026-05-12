package com.mannschaft.app.repairplan.repository;

import com.mannschaft.app.common.repository.AbstractTenantAwareRepository;
import com.mannschaft.app.repairplan.entity.RepairPlanItem;

import java.util.List;
import java.util.UUID;

/**
 * 修繕計画項目リポジトリ。
 */
public interface RepairPlanItemRepository extends AbstractTenantAwareRepository<RepairPlanItem, UUID> {

    /** スコープ単位の計画項目を取得（年度昇順）。 */
    List<RepairPlanItem> findByScopeTypeAndScopeIdAndDeletedAtIsNullOrderByPlannedYearAsc(
            String scopeType, Long scopeId);

    /** スコープ × ステータスの計画項目を取得。 */
    List<RepairPlanItem> findByScopeTypeAndScopeIdAndStatusAndDeletedAtIsNull(
            String scopeType, Long scopeId, String status);

    /** F09.13 物件 work_package からの逆引き。 */
    List<RepairPlanItem> findByLinkedWorkPackageIdAndDeletedAtIsNull(Long linkedWorkPackageId);
}
