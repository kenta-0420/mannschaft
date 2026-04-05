package com.mannschaft.app.role.repository;

import com.mannschaft.app.role.entity.PermissionGroupEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * パーミッショングループリポジトリ。
 */
public interface PermissionGroupRepository extends JpaRepository<PermissionGroupEntity, Long> {

    List<PermissionGroupEntity> findByTeamId(Long teamId);

    List<PermissionGroupEntity> findByOrganizationId(Long organizationId);
}
