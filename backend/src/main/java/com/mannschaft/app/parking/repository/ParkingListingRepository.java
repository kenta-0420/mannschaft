package com.mannschaft.app.parking.repository;

import com.mannschaft.app.parking.ListingStatus;
import com.mannschaft.app.parking.entity.ParkingListingEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 譲渡希望リポジトリ。
 */
public interface ParkingListingRepository extends JpaRepository<ParkingListingEntity, Long> {

    Page<ParkingListingEntity> findBySpaceIdIn(List<Long> spaceIds, Pageable pageable);

    Page<ParkingListingEntity> findBySpaceIdInAndStatus(List<Long> spaceIds, ListingStatus status, Pageable pageable);

    Optional<ParkingListingEntity> findByIdAndSpaceIdIn(Long id, List<Long> spaceIds);

    long countBySpaceIdInAndStatus(List<Long> spaceIds, ListingStatus status);
}
