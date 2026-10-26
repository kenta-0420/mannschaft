package com.mannschaft.app.chart.repository;

import com.mannschaft.app.chart.entity.ChartCustomFieldEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * カスタムフィールドリポジトリ。
 */
public interface ChartCustomFieldRepository extends JpaRepository<ChartCustomFieldEntity, Long> {

    List<ChartCustomFieldEntity> findByTeamIdOrderBySortOrder(Long teamId);

    List<ChartCustomFieldEntity> findByTeamIdAndIsActiveTrueOrderBySortOrder(Long teamId);

    Optional<ChartCustomFieldEntity> findByIdAndTeamId(Long id, Long teamId);

    long countByTeamIdAndIsActiveTrue(Long teamId);

    List<ChartCustomFieldEntity> findByTeamIdAndFieldTypeOrderBySortOrder(Long teamId, String fieldType);
}
