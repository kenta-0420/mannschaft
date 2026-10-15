package com.mannschaft.app.chart.repository;

import com.mannschaft.app.chart.entity.ChartBodyMarkEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 身体チャートマークリポジトリ。
 */
public interface ChartBodyMarkRepository extends JpaRepository<ChartBodyMarkEntity, Long> {

    List<ChartBodyMarkEntity> findByChartRecordId(Long chartRecordId);

    void deleteByChartRecordId(Long chartRecordId);
}
