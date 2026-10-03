package com.mannschaft.app.chart.repository;

import com.mannschaft.app.chart.entity.ChartPhotoEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * カルテ写真リポジトリ。
 */
public interface ChartPhotoRepository extends JpaRepository<ChartPhotoEntity, Long> {

    List<ChartPhotoEntity> findByChartRecordIdOrderBySortOrder(Long chartRecordId);

    List<ChartPhotoEntity> findByChartRecordIdAndIsSharedToCustomerTrueOrderBySortOrder(Long chartRecordId);

    long countByChartRecordId(Long chartRecordId);

    /**
     * 複数カルテの写真件数を GROUP BY で一括取得する（一覧の N+1 回避用）。
     * 写真が0枚のカルテは結果に含まれないため、呼び出し側で 0 補完すること。
     * 各要素は {@code [chartRecordId(Long), count(Long)]}。
     */
    @Query("SELECT p.chartRecordId, COUNT(p) FROM ChartPhotoEntity p "
            + "WHERE p.chartRecordId IN :chartRecordIds GROUP BY p.chartRecordId")
    List<Object[]> countGroupedByChartRecordIds(@Param("chartRecordIds") Collection<Long> chartRecordIds);
}
