package com.mannschaft.app.role.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * スコープごとの role-permissions キャッシュ世代。
 *
 * <p>行が存在しないスコープは初期世代 {@code 0} とみなす。世代を進める操作は
 * {@code INSERT ... ON DUPLICATE KEY UPDATE} で行い、並行した権限変更でも欠番なく単調増加する。</p>
 */
@Entity
@Table(name = "role_permission_cache_generations", uniqueConstraints = @UniqueConstraint(
        name = "uq_role_permission_cache_generations_scope", columnNames = {"scope_type", "scope_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RolePermissionCacheGenerationEntity extends UuidV7Entity {

    @Column(name = "scope_type", nullable = false, length = 20)
    private String scopeType;

    @Column(name = "scope_id", nullable = false)
    private Long scopeId;

    @Column(nullable = false)
    private Long generation;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
