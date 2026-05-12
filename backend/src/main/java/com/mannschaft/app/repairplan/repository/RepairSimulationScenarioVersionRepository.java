package com.mannschaft.app.repairplan.repository;

import com.mannschaft.app.repairplan.entity.RepairSimulationScenarioVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 修繕シミュレーションシナリオの不変バージョンリポジトリ。
 *
 * <p>論理削除を持たない不変テーブルのため {@code AbstractTenantAwareRepository} は継承せず
 * {@link JpaRepository} を直接継承する。テナント絞り込みは独自メソッドで提供する。</p>
 */
public interface RepairSimulationScenarioVersionRepository
        extends JpaRepository<RepairSimulationScenarioVersion, UUID> {

    /** シナリオ ID 単位のバージョン履歴（バージョン番号昇順）。 */
    List<RepairSimulationScenarioVersion> findByScenarioIdOrderByVersionNoAsc(UUID scenarioId);

    /** シナリオ ID 単位の最新バージョン取得。 */
    Optional<RepairSimulationScenarioVersion> findFirstByScenarioIdOrderByVersionNoDesc(UUID scenarioId);

    /** テナント単位の全バージョン件数。 */
    long countByOrganizationId(Long organizationId);
}
