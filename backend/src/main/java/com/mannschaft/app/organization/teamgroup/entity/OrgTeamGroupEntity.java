package com.mannschaft.app.organization.teamgroup.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * チームグループ（F01.2.1 §5.2）。組織に加盟するチームを区分する平坦・並び順付きの論理削除エンティティ。
 */
@Entity
@Table(name = "org_team_groups",
        uniqueConstraints = @UniqueConstraint(name = "uq_org_team_groups_org_active_name",
                columnNames = {"organization_id", "active_name"}),
        indexes = @Index(name = "idx_org_team_groups_org_sort",
                columnList = "organization_id, deleted_at, sort_order"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class OrgTeamGroupEntity extends UuidV7Entity {

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    @Column(name = "description", length = 200)
    private String description;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /** 生存行だけの名前（生成列。削除済みは NULL）。DB が計算するため読み取り専用。 */
    @Column(name = "active_name", insertable = false, updatable = false,
            columnDefinition = "VARCHAR(50) GENERATED ALWAYS AS (IF(deleted_at IS NULL, name, NULL)) STORED")
    private String activeName;

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
}
