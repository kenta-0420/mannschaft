package com.mannschaft.app.organization;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 組織ブロックリポジトリ。
 */
public interface OrganizationBlockRepository extends JpaRepository<OrganizationBlockEntity, Long> {

    boolean existsByOrganizationIdAndUserId(Long organizationId, Long userId);

    Optional<OrganizationBlockEntity> findByOrganizationIdAndUserId(Long organizationId, Long userId);

    List<OrganizationBlockEntity> findByOrganizationId(Long organizationId);
}
