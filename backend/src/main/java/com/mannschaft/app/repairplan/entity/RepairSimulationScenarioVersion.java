package com.mannschaft.app.repairplan.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.experimental.SuperBuilder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 修繕シミュレーションシナリオの不変バージョン（F08.8 Phase 1）。
 *
 * <p>議案変換時の完全スナップショット。DB トリガで UPDATE / DELETE は常に拒否される。
 * 論理削除カラムも持たない（不変保証）。</p>
 */
@Entity
@Table(name = "repair_simulation_scenario_versions")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class RepairSimulationScenarioVersion extends UuidV7Entity {

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "scenario_id", nullable = false)
    private UUID scenarioId;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Column(name = "params_snapshot", nullable = false, columnDefinition = "JSON")
    private String paramsSnapshot;

    @Column(name = "computed_summary_snapshot", nullable = false, columnDefinition = "JSON")
    private String computedSummarySnapshot;

    @Column(name = "engine_version", nullable = false, length = 20)
    private String engineVersion;

    @Column(name = "content_sha256", nullable = false, length = 64)
    private String contentSha256;

    @Column(name = "proposed_resolution_no", length = 100)
    private String proposedResolutionNo;

    @Column(name = "locked_by", nullable = false)
    private Long lockedBy;

    @Column(name = "locked_at", nullable = false, updatable = false)
    private LocalDateTime lockedAt;

    @PrePersist
    protected void onCreate() {
        if (this.lockedAt == null) {
            this.lockedAt = LocalDateTime.now();
        }
    }
}
