package com.mannschaft.app.parking.repository;

import com.mannschaft.app.parking.SubleaseStatus;
import com.mannschaft.app.parking.entity.ParkingSubleaseEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * サブリースリポジトリ。
 */
public interface ParkingSubleaseRepository extends JpaRepository<ParkingSubleaseEntity, Long> {

    Page<ParkingSubleaseEntity> findBySpaceIdIn(List<Long> spaceIds, Pageable pageable);

    Page<ParkingSubleaseEntity> findBySpaceIdInAndStatus(List<Long> spaceIds, SubleaseStatus status, Pageable pageable);

    Optional<ParkingSubleaseEntity> findByIdAndSpaceIdIn(Long id, List<Long> spaceIds);

    long countBySpaceIdInAndStatus(List<Long> spaceIds, SubleaseStatus status);
}
