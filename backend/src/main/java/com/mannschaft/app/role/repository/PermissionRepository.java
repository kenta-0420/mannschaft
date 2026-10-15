package com.mannschaft.app.role.repository;

import com.mannschaft.app.role.entity.PermissionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * パーミッションリポジトリ。
 */
public interface PermissionRepository extends JpaRepository<PermissionEntity, Long> {

    List<PermissionEntity> findByScope(PermissionEntity.Scope scope);

    List<PermissionEntity> findByScopeIn(java.util.Collection<PermissionEntity.Scope> scopes);

    List<PermissionEntity> findByIdIn(List<Long> ids);

    List<PermissionEntity> findByNameIn(List<String> names);
}
