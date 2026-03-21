package com.mannschaft.app.chart.repository;

import com.mannschaft.app.chart.entity.ChartPhotoEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * カルテ写真リポジトリ。
 */
public interface ChartPhotoRepository extends JpaRepository<ChartPhotoEntity, Long> {

    List<ChartPhotoEntity> findByChartRecordIdOrderBySortOrder(Long chartRecordId);

    List<ChartPhotoEntity> findByChartRecordIdAndIsSharedToCustomerTrueOrderBySortOrder(Long chartRecordId);

    long countByChartRecordId(Long chartRecordId);
}
