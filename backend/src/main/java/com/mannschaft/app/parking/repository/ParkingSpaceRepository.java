package com.mannschaft.app.parking.repository;

import com.mannschaft.app.parking.SpaceStatus;
import com.mannschaft.app.parking.SpaceType;
import com.mannschaft.app.parking.entity.ParkingSpaceEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 駐車区画リポジトリ。
 */
public interface ParkingSpaceRepository extends JpaRepository<ParkingSpaceEntity, Long> {

    Page<ParkingSpaceEntity> findByScopeTypeAndScopeId(String scopeType, Long scopeId, Pageable pageable);

    Page<ParkingSpaceEntity> findByScopeTypeAndScopeIdAndStatus(String scopeType, Long scopeId, SpaceStatus status, Pageable pageable);

    Page<ParkingSpaceEntity> findByScopeTypeAndScopeIdAndSpaceType(String scopeType, Long scopeId, SpaceType spaceType, Pageable pageable);

    Page<ParkingSpaceEntity> findByScopeTypeAndScopeIdAndFloor(String scopeType, Long scopeId, String floor, Pageable pageable);

    List<ParkingSpaceEntity> findByScopeTypeAndScopeIdAndStatus(String scopeType, Long scopeId, SpaceStatus status);

    Optional<ParkingSpaceEntity> findByIdAndScopeTypeAndScopeId(Long id, String scopeType, Long scopeId);

    long countByScopeTypeAndScopeId(String scopeType, Long scopeId);

    long countByScopeTypeAndScopeIdAndStatus(String scopeType, Long scopeId, SpaceStatus status);

    @Query("SELECT DISTINCT e.floor FROM ParkingSpaceEntity e WHERE e.scopeType = :scopeType AND e.scopeId = :scopeId AND e.floor IS NOT NULL ORDER BY e.floor ASC")
    List<String> findDistinctFloorsByScopeTypeAndScopeId(@Param("scopeType") String scopeType, @Param("scopeId") Long scopeId);
}
