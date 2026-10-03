package com.mannschaft.app.role.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Instant;

/** チーム・組織ごとのロール既定権限上書き。 */
@Entity
@Table(name = "team_role_permissions", uniqueConstraints = @UniqueConstraint(
        name = "uq_team_role_permissions_scope_role_permission",
        columnNames = {"scope_type", "scope_id", "role_id", "permission_id"}))
@Getter
@SuperBuilder(toBuilder = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TeamRolePermissionEntity extends UuidV7Entity {

    @Column(name = "scope_type", nullable = false, length = 20)
    private String scopeType;

    @Column(name = "scope_id", nullable = false)
    private Long scopeId;

    @Column(name = "role_id", nullable = false)
    private Long roleId;

    @Column(name = "permission_id", nullable = false)
    private Long permissionId;

    @Column(name = "is_enabled", nullable = false)
    private Boolean isEnabled;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
