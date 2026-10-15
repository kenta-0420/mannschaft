package com.mannschaft.app.parking.repository;

import com.mannschaft.app.parking.entity.ParkingWatchlistEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * ウォッチリストリポジトリ。
 */
public interface ParkingWatchlistRepository extends JpaRepository<ParkingWatchlistEntity, Long> {

    List<ParkingWatchlistEntity> findByUserIdAndScopeTypeAndScopeIdAndIsActiveTrue(Long userId, String scopeType, Long scopeId);

    Optional<ParkingWatchlistEntity> findByIdAndUserIdAndScopeTypeAndScopeId(Long id, Long userId, String scopeType, Long scopeId);
}
