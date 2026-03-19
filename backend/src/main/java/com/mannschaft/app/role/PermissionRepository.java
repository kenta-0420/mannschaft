package com.mannschaft.app.role;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * パーミッションリポジトリ。
 */
public interface PermissionRepository extends JpaRepository<PermissionEntity, Long> {

    List<PermissionEntity> findByScope(PermissionEntity.Scope scope);

    List<PermissionEntity> findByIdIn(List<Long> ids);
}
