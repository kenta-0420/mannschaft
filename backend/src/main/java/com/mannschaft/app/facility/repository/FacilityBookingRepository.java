package com.mannschaft.app.facility.repository;

import com.mannschaft.app.facility.BookingStatus;
import com.mannschaft.app.facility.entity.FacilityBookingEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * 施設予約リポジトリ。
 */
public interface FacilityBookingRepository extends JpaRepository<FacilityBookingEntity, Long> {

    @Query("SELECT b FROM FacilityBookingEntity b JOIN SharedFacilityEntity f ON b.facilityId = f.id "
            + "WHERE f.scopeType = :scopeType AND f.scopeId = :scopeId ORDER BY b.bookingDate DESC, b.timeFrom ASC")
    Page<FacilityBookingEntity> findByScopeOrderByBookingDateDesc(
            @Param("scopeType") String scopeType, @Param("scopeId") Long scopeId, Pageable pageable);

    @Query("SELECT b FROM FacilityBookingEntity b JOIN SharedFacilityEntity f ON b.facilityId = f.id "
            + "WHERE f.scopeType = :scopeType AND f.scopeId = :scopeId AND b.status = :status "
            + "ORDER BY b.bookingDate DESC, b.timeFrom ASC")
    Page<FacilityBookingEntity> findByScopeAndStatusOrderByBookingDateDesc(
            @Param("scopeType") String scopeType, @Param("scopeId") Long scopeId,
            @Param("status") BookingStatus status, Pageable pageable);

    Optional<FacilityBookingEntity> findById(Long id);

    List<FacilityBookingEntity> findByFacilityIdAndBookingDateAndStatusNotIn(
            Long facilityId, LocalDate bookingDate, List<BookingStatus> excludeStatuses);

    @Query("SELECT b FROM FacilityBookingEntity b JOIN SharedFacilityEntity f ON b.facilityId = f.id "
            + "WHERE f.scopeType = :scopeType AND f.scopeId = :scopeId "
            + "AND b.bookingDate BETWEEN :dateFrom AND :dateTo "
            + "AND b.status NOT IN :excludeStatuses "
            + "ORDER BY b.bookingDate ASC, b.timeFrom ASC")
    List<FacilityBookingEntity> findCalendarBookings(
            @Param("scopeType") String scopeType, @Param("scopeId") Long scopeId,
            @Param("dateFrom") LocalDate dateFrom, @Param("dateTo") LocalDate dateTo,
            @Param("excludeStatuses") List<BookingStatus> excludeStatuses);

    long countByFacilityIdAndBookingDateAndBookedByAndStatusNotIn(
            Long facilityId, LocalDate bookingDate, Long bookedBy, List<BookingStatus> excludeStatuses);

    @Query("SELECT COUNT(b) FROM FacilityBookingEntity b "
            + "WHERE b.bookedBy = :userId AND b.facilityId = :facilityId "
            + "AND YEAR(b.bookingDate) = :year AND MONTH(b.bookingDate) = :month "
            + "AND b.status NOT IN :excludeStatuses")
    long countMonthlyBookings(
            @Param("userId") Long userId, @Param("facilityId") Long facilityId,
            @Param("year") int year, @Param("month") int month,
            @Param("excludeStatuses") List<BookingStatus> excludeStatuses);

    @Query("SELECT b FROM FacilityBookingEntity b "
            + "WHERE b.facilityId = :facilityId AND b.bookingDate = :date "
            + "AND b.status NOT IN :excludeStatuses "
            + "AND ((b.timeFrom < :timeTo AND b.timeTo > :timeFrom))")
    List<FacilityBookingEntity> findOverlapping(
            @Param("facilityId") Long facilityId, @Param("date") LocalDate date,
            @Param("timeFrom") LocalTime timeFrom, @Param("timeTo") LocalTime timeTo,
            @Param("excludeStatuses") List<BookingStatus> excludeStatuses);

    @Query("SELECT COUNT(b) FROM FacilityBookingEntity b JOIN SharedFacilityEntity f ON b.facilityId = f.id "
            + "WHERE f.scopeType = :scopeType AND f.scopeId = :scopeId AND b.status = :status")
    long countByScopeAndStatus(
            @Param("scopeType") String scopeType, @Param("scopeId") Long scopeId,
            @Param("status") BookingStatus status);
}
