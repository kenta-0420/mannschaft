package com.mannschaft.app.organization.repository;

import com.mannschaft.app.organization.entity.OrganizationOfficerEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 組織役員リポジトリ。
 */
public interface OrganizationOfficerRepository extends JpaRepository<OrganizationOfficerEntity, Long> {

    List<OrganizationOfficerEntity> findByOrganizationIdOrderByDisplayOrderAsc(Long organizationId);

    int countByOrganizationId(Long organizationId);

    void deleteByOrganizationId(Long organizationId);
}
