package com.mannschaft.app.incident.repository;

import com.mannschaft.app.incident.entity.IncidentAssignmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * インシデント担当者リポジトリ。
 */
public interface IncidentAssignmentRepository extends JpaRepository<IncidentAssignmentEntity, Long> {

    /**
     * インシデント ID に紐づく担当者一覧を取得する。
     */
    List<IncidentAssignmentEntity> findByIncidentId(Long incidentId);

    /**
     * インシデント ID とユーザー ID で担当者を取得する。
     */
    Optional<IncidentAssignmentEntity> findByIncidentIdAndUserId(Long incidentId, Long userId);
}
