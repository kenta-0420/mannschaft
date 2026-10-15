package com.mannschaft.app.role.repository;

import com.mannschaft.app.role.entity.PermissionGroupPermissionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * パーミッショングループ−パーミッション関連リポジトリ。
 */
public interface PermissionGroupPermissionRepository extends JpaRepository<PermissionGroupPermissionEntity, Long> {

    List<PermissionGroupPermissionEntity> findByGroupId(Long groupId);

    void deleteByGroupId(Long groupId);
}
