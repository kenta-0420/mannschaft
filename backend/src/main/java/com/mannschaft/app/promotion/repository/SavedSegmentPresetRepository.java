package com.mannschaft.app.promotion.repository;

import com.mannschaft.app.promotion.entity.SavedSegmentPresetEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * セグメントプリセットリポジトリ。
 */
public interface SavedSegmentPresetRepository extends JpaRepository<SavedSegmentPresetEntity, Long> {

    @Query("SELECT sp FROM SavedSegmentPresetEntity sp WHERE sp.scopeType = :scopeType AND sp.scopeId = :scopeId ORDER BY sp.name")
    List<SavedSegmentPresetEntity> findByScopeTypeAndScopeId(
            @Param("scopeType") String scopeType,
            @Param("scopeId") Long scopeId);

    @Query("SELECT sp FROM SavedSegmentPresetEntity sp WHERE sp.id = :id AND sp.scopeType = :scopeType AND sp.scopeId = :scopeId")
    Optional<SavedSegmentPresetEntity> findByIdAndScope(
            @Param("id") Long id,
            @Param("scopeType") String scopeType,
            @Param("scopeId") Long scopeId);
}
