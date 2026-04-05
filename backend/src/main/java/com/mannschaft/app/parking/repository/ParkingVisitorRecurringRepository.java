package com.mannschaft.app.parking.repository;

import com.mannschaft.app.parking.entity.ParkingVisitorRecurringEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 定期来場者予約テンプレートリポジトリ。
 */
public interface ParkingVisitorRecurringRepository extends JpaRepository<ParkingVisitorRecurringEntity, Long> {

    List<ParkingVisitorRecurringEntity> findByUserIdAndScopeTypeAndScopeIdAndIsActiveTrue(Long userId, String scopeType, Long scopeId);

    Optional<ParkingVisitorRecurringEntity> findByIdAndUserIdAndScopeTypeAndScopeId(Long id, Long userId, String scopeType, Long scopeId);
}
