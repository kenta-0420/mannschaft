package com.mannschaft.app.chart.repository;

import com.mannschaft.app.chart.entity.ChartRecordTemplateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * カルテテンプレートリポジトリ。
 */
public interface ChartRecordTemplateRepository extends JpaRepository<ChartRecordTemplateEntity, Long> {

    List<ChartRecordTemplateEntity> findByTeamIdOrderBySortOrder(Long teamId);

    Optional<ChartRecordTemplateEntity> findByIdAndTeamId(Long id, Long teamId);

    long countByTeamId(Long teamId);
}
