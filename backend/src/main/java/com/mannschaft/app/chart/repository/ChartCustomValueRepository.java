package com.mannschaft.app.chart.repository;

import com.mannschaft.app.chart.entity.ChartCustomValueEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * カスタム値リポジトリ。
 */
public interface ChartCustomValueRepository extends JpaRepository<ChartCustomValueEntity, Long> {

    List<ChartCustomValueEntity> findByChartRecordId(Long chartRecordId);

    List<ChartCustomValueEntity> findByChartRecordIdIn(List<Long> chartRecordIds);

    void deleteByChartRecordId(Long chartRecordId);
}
