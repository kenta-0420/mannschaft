package com.mannschaft.app.chart.repository;

import com.mannschaft.app.chart.entity.ChartFormulaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 薬剤レシピリポジトリ。
 */
public interface ChartFormulaRepository extends JpaRepository<ChartFormulaEntity, Long> {

    List<ChartFormulaEntity> findByChartRecordIdOrderBySortOrder(Long chartRecordId);

    long countByChartRecordId(Long chartRecordId);

    Optional<ChartFormulaEntity> findByIdAndChartRecordId(Long id, Long chartRecordId);
}
