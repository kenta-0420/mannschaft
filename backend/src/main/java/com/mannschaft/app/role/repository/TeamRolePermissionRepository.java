package com.mannschaft.app.role.repository;

import com.mannschaft.app.role.entity.TeamRolePermissionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** スコープ別ロール既定権限上書きリポジトリ。 */
public interface TeamRolePermissionRepository extends JpaRepository<TeamRolePermissionEntity, UUID> {

    List<TeamRolePermissionEntity> findByScopeTypeAndScopeIdAndRoleId(
            String scopeType, Long scopeId, Long roleId);

    Optional<TeamRolePermissionEntity> findByScopeTypeAndScopeIdAndRoleIdAndPermissionId(
            String scopeType, Long scopeId, Long roleId, Long permissionId);
}
