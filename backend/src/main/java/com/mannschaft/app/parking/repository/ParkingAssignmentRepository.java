package com.mannschaft.app.parking.repository;

import com.mannschaft.app.parking.entity.ParkingAssignmentEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 区画割り当てリポジトリ。
 */
public interface ParkingAssignmentRepository extends JpaRepository<ParkingAssignmentEntity, Long> {

    Optional<ParkingAssignmentEntity> findBySpaceIdAndReleasedAtIsNull(Long spaceId);

    List<ParkingAssignmentEntity> findByUserIdAndReleasedAtIsNull(Long userId);

    long countByUserIdAndReleasedAtIsNull(Long userId);

    Page<ParkingAssignmentEntity> findBySpaceId(Long spaceId, Pageable pageable);

    List<ParkingAssignmentEntity> findBySpaceIdInAndReleasedAtIsNull(List<Long> spaceIds);
}
