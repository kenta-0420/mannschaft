package com.mannschaft.app.parking.repository;

import com.mannschaft.app.parking.entity.ParkingSpacePriceHistoryEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 区画料金履歴リポジトリ。
 */
public interface ParkingSpacePriceHistoryRepository extends JpaRepository<ParkingSpacePriceHistoryEntity, Long> {

    Page<ParkingSpacePriceHistoryEntity> findBySpaceId(Long spaceId, Pageable pageable);
}
