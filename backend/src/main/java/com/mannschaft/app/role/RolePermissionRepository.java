package com.mannschaft.app.role;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * ロール−パーミッション関連リポジトリ。
 */
public interface RolePermissionRepository extends JpaRepository<RolePermissionEntity, Long> {

    List<RolePermissionEntity> findByRoleId(Long roleId);
}
