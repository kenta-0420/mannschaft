package com.mannschaft.app.incident.repository;

import com.mannschaft.app.incident.entity.IncidentCategoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * インシデントカテゴリリポジトリ。
 */
public interface IncidentCategoryRepository extends JpaRepository<IncidentCategoryEntity, Long> {

    /**
     * スコープに紐づく有効カテゴリ（未削除・有効）を取得する。
     */
    List<IncidentCategoryEntity> findByScopeTypeAndScopeIdAndIsActiveTrueAndDeletedAtIsNull(
            String scopeType, Long scopeId);
}
