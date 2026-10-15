package com.mannschaft.app.facility.repository;

import com.mannschaft.app.facility.entity.FacilitySettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 施設予約設定リポジトリ。
 */
public interface FacilitySettingsRepository extends JpaRepository<FacilitySettingsEntity, Long> {

    Optional<FacilitySettingsEntity> findByScopeTypeAndScopeId(String scopeType, Long scopeId);
}
