package com.mannschaft.app.organization.repository;

import com.mannschaft.app.organization.entity.OrganizationCustomFieldEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 組織カスタムフィールドリポジトリ。
 */
public interface OrganizationCustomFieldRepository extends JpaRepository<OrganizationCustomFieldEntity, Long> {

    List<OrganizationCustomFieldEntity> findByOrganizationIdOrderByDisplayOrderAsc(Long organizationId);

    int countByOrganizationId(Long organizationId);

    void deleteByOrganizationId(Long organizationId);
}
