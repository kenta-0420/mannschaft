package com.mannschaft.app.repairplan.repository;

import com.mannschaft.app.common.repository.AbstractTenantAwareRepository;
import com.mannschaft.app.repairplan.entity.RepairPlanTemplate;

import java.util.List;
import java.util.UUID;

/**
 * 修繕周期マスタリポジトリ。
 *
 * <p>SYSTEM 行は {@code organization_id IS NULL} で保持されるため、SYSTEM 行取得には
 * 専用メソッド {@link #findBySystemScope()} を使用する。
 * テナント側のオーバーライド行は {@code AbstractTenantAwareRepository} の共通メソッドで取得可能。</p>
 */
public interface RepairPlanTemplateRepository extends AbstractTenantAwareRepository<RepairPlanTemplate, UUID> {

    /** SYSTEM スコープのテンプレート全件取得。 */
    List<RepairPlanTemplate> findByScopeTypeAndDeletedAtIsNull(String scopeType);

    /** SYSTEM スコープのテンプレート全件取得（便利メソッド）。 */
    default List<RepairPlanTemplate> findBySystemScope() {
        return findByScopeTypeAndDeletedAtIsNull("SYSTEM");
    }

    /** スコープ単位のテンプレート取得（ORG/TEAM 用）。 */
    List<RepairPlanTemplate> findByScopeTypeAndScopeIdAndDeletedAtIsNull(String scopeType, Long scopeId);

    /** スコープ × カテゴリでのオーバーライド存在チェック。 */
    List<RepairPlanTemplate> findByScopeTypeAndScopeIdAndCategoryAndDeletedAtIsNull(
            String scopeType, Long scopeId, String category);
}
