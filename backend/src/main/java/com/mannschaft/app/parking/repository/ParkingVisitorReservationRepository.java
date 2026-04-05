package com.mannschaft.app.parking.repository;

import com.mannschaft.app.parking.VisitorReservationStatus;
import com.mannschaft.app.parking.entity.ParkingVisitorReservationEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

/**
 * 来場者予約リポジトリ。
 */
public interface ParkingVisitorReservationRepository extends JpaRepository<ParkingVisitorReservationEntity, Long> {

    Page<ParkingVisitorReservationEntity> findBySpaceIdIn(List<Long> spaceIds, Pageable pageable);

    Page<ParkingVisitorReservationEntity> findBySpaceIdInAndReservedDate(List<Long> spaceIds, LocalDate date, Pageable pageable);

    long countByReservedByAndReservedDateAndStatusNotIn(Long reservedBy, LocalDate date, List<VisitorReservationStatus> excludeStatuses);

    List<ParkingVisitorReservationEntity> findBySpaceIdAndReservedDateAndStatusNotIn(Long spaceId, LocalDate date, List<VisitorReservationStatus> excludeStatuses);

    List<ParkingVisitorReservationEntity> findBySpaceIdInAndReservedDateAndStatusNotIn(List<Long> spaceIds, LocalDate date, List<VisitorReservationStatus> excludeStatuses);
}
