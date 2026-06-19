package com.mannschaft.app.repairplan.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.experimental.SuperBuilder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * 修繕シミュレーションシナリオ（F08.8 Phase 1）。
 *
 * <p>積立金枯渇シミュレーションの保存シナリオ。
 * {@code content_sha256} で改ざん検出、{@code locked_at} セット後は
 * DB トリガで UPDATE 拒否される（議案変換時の不変保証）。</p>
 */
@Entity
@Table(name = "repair_simulation_scenarios")
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class RepairSimulationScenario extends UuidV7Entity {

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "scope_type", nullable = false, length = 20)
    private String scopeType;

    @Column(name = "scope_id", nullable = false)
    private Long scopeId;

    @Column(name = "name", length = 200)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "params_json", nullable = false, columnDefinition = "JSON")
    private String paramsJson;

    @Column(name = "computed_summary_json", nullable = false, columnDefinition = "JSON")
    private String computedSummaryJson;

    @Column(name = "engine_version", nullable = false, length = 20)
    private String engineVersion;

    @Column(name = "content_sha256", nullable = false, length = 64)
    private String contentSha256;

    @Column(name = "baseline_at", nullable = false)
    private LocalDateTime baselineAt;

    @Column(name = "locked_at")
    private LocalDateTime lockedAt;

    /** F02.8 dashboard_announcements.id（クロスドメイン参照・FK なし） */
    @Column(name = "published_announcement_id")
    private Long publishedAnnouncementId;

    /** F09.8 corkboard_pins.id（クロスドメイン参照・FK なし） */
    @Column(name = "pinned_corkboard_id")
    private Long pinnedCorkboardId;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
