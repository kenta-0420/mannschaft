package com.mannschaft.app.activity.repository;

import com.mannschaft.app.activity.entity.ActivityTemplateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 活動記録テンプレートリポジトリ。
 */
public interface ActivityTemplateRepository extends JpaRepository<ActivityTemplateEntity, Long> {

    List<ActivityTemplateEntity> findByTeamIdOrderByCreatedAtDesc(Long teamId);

    List<ActivityTemplateEntity> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId);

    List<ActivityTemplateEntity> findByScopeTypeAndIsOfficialTrue(String scopeType);

    Optional<ActivityTemplateEntity> findByShareCode(String shareCode);

    long countByTeamId(Long teamId);

    long countByOrganizationId(Long organizationId);
}
