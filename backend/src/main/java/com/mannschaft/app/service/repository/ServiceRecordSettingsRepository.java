package com.mannschaft.app.service.repository;

import com.mannschaft.app.service.entity.ServiceRecordSettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 機能設定リポジトリ。
 */
public interface ServiceRecordSettingsRepository extends JpaRepository<ServiceRecordSettingsEntity, Long> {

    Optional<ServiceRecordSettingsEntity> findByTeamId(Long teamId);

    List<ServiceRecordSettingsEntity> findByTeamIdInAndIsDashboardEnabledTrue(List<Long> teamIds);
}
