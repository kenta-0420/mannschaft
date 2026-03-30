package com.mannschaft.app.incident.repository;

import com.mannschaft.app.incident.entity.IncidentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * インシデントリポジトリ。
 */
public interface IncidentRepository extends JpaRepository<IncidentEntity, Long> {

    /**
     * ID で未削除のインシデントを取得する。
     */
    Optional<IncidentEntity> findByIdAndDeletedAtIsNull(Long id);

    /**
     * スコープに紐づく未削除インシデントを作成日時降順で取得する。
     */
    List<IncidentEntity> findByScopeTypeAndScopeIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            String scopeType, Long scopeId);

    /**
     * 報告者 ID で未削除インシデントを取得する。
     */
    List<IncidentEntity> findByReportedByAndDeletedAtIsNull(Long reportedBy);
}
