package com.mannschaft.app.parking.repository;

import com.mannschaft.app.parking.entity.ParkingSettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 駐車場設定リポジトリ。
 */
public interface ParkingSettingsRepository extends JpaRepository<ParkingSettingsEntity, Long> {

    Optional<ParkingSettingsEntity> findByScopeTypeAndScopeId(String scopeType, Long scopeId);
}
