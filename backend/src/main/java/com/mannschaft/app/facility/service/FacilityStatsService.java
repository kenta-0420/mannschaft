package com.mannschaft.app.facility.service;

import com.mannschaft.app.facility.BookingStatus;
import com.mannschaft.app.facility.dto.FacilityStatsResponse;
import com.mannschaft.app.facility.repository.FacilityBookingDailyStatsRepository;
import com.mannschaft.app.facility.repository.FacilityBookingRepository;
import com.mannschaft.app.facility.repository.SharedFacilityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 施設統計サービス。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FacilityStatsService {

    private final SharedFacilityRepository facilityRepository;
    private final FacilityBookingRepository bookingRepository;
    private final FacilityBookingDailyStatsRepository dailyStatsRepository;

    /**
     * 施設統計を取得する。
     */
    public FacilityStatsResponse getStats(String scopeType, Long scopeId) {
        int totalFacilities = facilityRepository.countByScopeTypeAndScopeId(scopeType, scopeId);
        int activeFacilities = facilityRepository.countByScopeTypeAndScopeIdAndIsActiveTrue(scopeType, scopeId);

        long completedBookings = bookingRepository.countByScopeAndStatus(
                scopeType, scopeId, BookingStatus.COMPLETED);
        long cancelledBookings = bookingRepository.countByScopeAndStatus(
                scopeType, scopeId, BookingStatus.CANCELLED);
        long noShowBookings = bookingRepository.countByScopeAndStatus(
                scopeType, scopeId, BookingStatus.NO_SHOW);

        long totalBookings = completedBookings + cancelledBookings + noShowBookings
                + bookingRepository.countByScopeAndStatus(scopeType, scopeId, BookingStatus.PENDING_APPROVAL)
                + bookingRepository.countByScopeAndStatus(scopeType, scopeId, BookingStatus.CONFIRMED)
                + bookingRepository.countByScopeAndStatus(scopeType, scopeId, BookingStatus.CHECKED_IN)
                + bookingRepository.countByScopeAndStatus(scopeType, scopeId, BookingStatus.REJECTED);

        BigDecimal totalRevenue = dailyStatsRepository.sumRevenueTotal(scopeType, scopeId);
        BigDecimal totalPlatformFee = dailyStatsRepository.sumPlatformFeeTotal(scopeType, scopeId);

        return new FacilityStatsResponse(
                totalFacilities, activeFacilities,
                totalBookings, completedBookings, cancelledBookings, noShowBookings,
                totalRevenue, totalPlatformFee
        );
    }
}
