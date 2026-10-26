package com.mannschaft.app.repairplan.repository;

import com.mannschaft.app.common.repository.AbstractTenantAwareRepository;
import com.mannschaft.app.repairplan.entity.RepairSimulationScenario;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 修繕シミュレーションシナリオリポジトリ。
 */
public interface RepairSimulationScenarioRepository
        extends AbstractTenantAwareRepository<RepairSimulationScenario, UUID> {

    /** スコープ単位のシナリオを最新順に取得。 */
    List<RepairSimulationScenario> findByScopeTypeAndScopeIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            String scopeType, Long scopeId);

    /** スコープあたりのアクティブシナリオ件数（上限 50 件チェック用）。 */
    long countByScopeTypeAndScopeIdAndDeletedAtIsNull(String scopeType, Long scopeId);

    /** content_sha256 による重複保存チェック。 */
    Optional<RepairSimulationScenario> findByScopeTypeAndScopeIdAndContentSha256AndDeletedAtIsNull(
            String scopeType, Long scopeId, String contentSha256);
}
