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
import java.util.UUID;

/**
 * 修繕計画項目（F08.8 Phase 1・案5）。
 *
 * <p>個別マンションの 30 年長期修繕計画項目。シミュレーターの予定支出ソース、
 * カンバンの親案件として参照される。{@code template_id} は同ドメイン内 FK で
 * {@code repair_plan_templates} を参照、{@code linked_work_package_id} は
 * F09.13 への ID 参照（FK なし）。</p>
 */
@Entity
@Table(name = "repair_plan_items")
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class RepairPlanItem extends UuidV7Entity {

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "scope_type", nullable = false, length = 20)
    private String scopeType;

    @Column(name = "scope_id", nullable = false)
    private Long scopeId;

    @Column(name = "template_id")
    private UUID templateId;

    @Column(name = "category", nullable = false, length = 60)
    private String category;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "planned_year", nullable = false)
    private Integer plannedYear;

    @Column(name = "planned_month")
    private Integer plannedMonth;

    @Column(name = "estimated_amount", nullable = false)
    private Long estimatedAmount;

    @Column(name = "cpi_inflation_basis_year", nullable = false)
    private Integer cpiInflationBasisYear;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /** F09.13 property_work_packages.id（クロスドメイン参照・FK なし） */
    @Column(name = "linked_work_package_id")
    private Long linkedWorkPackageId;

    @Column(name = "tags", columnDefinition = "JSON")
    private String tags;

    @Column(name = "minutes_note", columnDefinition = "TINYTEXT")
    private String minutesNote;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "updated_by")
    private Long updatedBy;

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
