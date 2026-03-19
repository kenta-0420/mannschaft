package com.mannschaft.app.family.repository;

import com.mannschaft.app.family.entity.DutyRotationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 当番ローテーションリポジトリ。
 */
public interface DutyRotationRepository extends JpaRepository<DutyRotationEntity, Long> {

    List<DutyRotationEntity> findByTeamIdAndDeletedAtIsNullOrderByCreatedAtAsc(Long teamId);

    List<DutyRotationEntity> findByTeamIdAndDeletedAtIsNullAndIsEnabledTrueOrderByCreatedAtAsc(Long teamId);

    long countByTeamIdAndDeletedAtIsNull(Long teamId);

    Optional<DutyRotationEntity> findByIdAndDeletedAtIsNull(Long id);
}
