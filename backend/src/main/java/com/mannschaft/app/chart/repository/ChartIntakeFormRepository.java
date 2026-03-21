package com.mannschaft.app.chart.repository;

import com.mannschaft.app.chart.entity.ChartIntakeFormEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 問診票リポジトリ。
 */
public interface ChartIntakeFormRepository extends JpaRepository<ChartIntakeFormEntity, Long> {

    List<ChartIntakeFormEntity> findByChartRecordId(Long chartRecordId);

    Optional<ChartIntakeFormEntity> findByChartRecordIdAndFormType(Long chartRecordId, String formType);

    void deleteByChartRecordId(Long chartRecordId);
}
