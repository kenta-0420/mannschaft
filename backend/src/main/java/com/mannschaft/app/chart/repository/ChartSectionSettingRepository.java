package com.mannschaft.app.chart.repository;

import com.mannschaft.app.chart.entity.ChartSectionSettingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * セクション設定リポジトリ。
 */
public interface ChartSectionSettingRepository extends JpaRepository<ChartSectionSettingEntity, Long> {

    List<ChartSectionSettingEntity> findByTeamId(Long teamId);

    Optional<ChartSectionSettingEntity> findByTeamIdAndSectionType(Long teamId, String sectionType);
}
