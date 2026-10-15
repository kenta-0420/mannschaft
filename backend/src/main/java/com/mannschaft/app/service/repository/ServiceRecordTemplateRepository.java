package com.mannschaft.app.service.repository;

import com.mannschaft.app.service.entity.ServiceRecordTemplateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * テンプレートリポジトリ。
 */
public interface ServiceRecordTemplateRepository extends JpaRepository<ServiceRecordTemplateEntity, Long> {

    List<ServiceRecordTemplateEntity> findByTeamIdOrderBySortOrder(Long teamId);

    List<ServiceRecordTemplateEntity> findByOrganizationIdOrderBySortOrder(Long organizationId);

    Optional<ServiceRecordTemplateEntity> findByIdAndTeamId(Long id, Long teamId);

    Optional<ServiceRecordTemplateEntity> findByIdAndOrganizationId(Long id, Long organizationId);

    long countByTeamId(Long teamId);

    long countByOrganizationId(Long organizationId);
}
