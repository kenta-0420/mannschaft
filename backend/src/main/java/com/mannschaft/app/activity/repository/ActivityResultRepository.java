package com.mannschaft.app.activity.repository;

import com.mannschaft.app.activity.entity.ActivityResultEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 活動記録リポジトリ。
 */
public interface ActivityResultRepository extends JpaRepository<ActivityResultEntity, Long> {

    Page<ActivityResultEntity> findByTeamIdOrderByActivityDateDesc(Long teamId, Pageable pageable);

    Page<ActivityResultEntity> findByOrganizationIdOrderByActivityDateDesc(Long organizationId, Pageable pageable);

    Page<ActivityResultEntity> findByTeamIdAndVisibilityOrderByActivityDateDesc(
            Long teamId, String visibility, Pageable pageable);

    Page<ActivityResultEntity> findByOrganizationIdAndVisibilityOrderByActivityDateDesc(
            Long organizationId, String visibility, Pageable pageable);

    Optional<ActivityResultEntity> findByScheduleEventId(Long scheduleEventId);

    long countByTemplateId(Long templateId);
}
