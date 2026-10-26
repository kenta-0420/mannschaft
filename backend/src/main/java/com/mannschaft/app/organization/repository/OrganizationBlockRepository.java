package com.mannschaft.app.organization.repository;

import com.mannschaft.app.organization.entity.OrganizationBlockEntity;
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
