package com.mannschaft.app.repairplan.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.experimental.SuperBuilder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * 修繕周期マスタ（F08.8 Phase 1）。
 *
 * <p>国土交通省「マンション修繕積立金ガイドライン（令和5年度改訂）」準拠の修繕周期マスタ。
 * SYSTEM seed → ORGANIZATION → TEAM の 3 層オーバーライドを単一テーブルで表現する。
 * SYSTEM 行のみ {@code organization_id} / {@code scopeId} が NULL。</p>
 */
@Entity
@Table(name = "repair_plan_templates")
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class RepairPlanTemplate extends UuidV7Entity {

    /** テナント絞り込み用。SYSTEM 行は NULL */
    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "scope_type", nullable = false, length = 20)
    private String scopeType;

    @Column(name = "scope_id")
    private Long scopeId;

    @Column(name = "category", nullable = false, length = 60)
    private String category;

    @Column(name = "cycle_years", nullable = false)
    private Integer cycleYears;

    @Column(name = "unit_cost_per_dwelling", nullable = false)
    private Long unitCostPerDwelling;

    @Column(name = "source_reference", length = 500)
    private String sourceReference;

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
