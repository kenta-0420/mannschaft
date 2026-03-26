package com.mannschaft.app.facility.service;

import com.mannschaft.app.facility.BookingStatus;
import com.mannschaft.app.facility.dto.FacilityStatsResponse;
import com.mannschaft.app.facility.repository.FacilityBookingDailyStatsRepository;
import com.mannschaft.app.facility.repository.FacilityBookingRepository;
import com.mannschaft.app.facility.repository.SharedFacilityRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * {@link FacilityStatsService} の単体テスト。
 * 施設統計の集計・合計値を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FacilityStatsService 単体テスト")
class FacilityStatsServiceTest {

    @Mock
    private SharedFacilityRepository facilityRepository;

    @Mock
    private FacilityBookingRepository bookingRepository;

    @Mock
    private FacilityBookingDailyStatsRepository dailyStatsRepository;

    @InjectMocks
    private FacilityStatsService statsService;

    // ========================================
    // テスト用定数
    // ========================================

    private static final String SCOPE_TYPE = "TEAM";
    private static final Long SCOPE_ID = 1L;

    // ========================================
    // getStats
    // ========================================

    @Nested
    @DisplayName("getStats")
    class GetStats {

        @Test
        @DisplayName("正常系: 統計情報が集計される")
        void 統計取得_正常_全項目が返る() {
            // Given
            given(facilityRepository.countByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID)).willReturn(5);
            given(facilityRepository.countByScopeTypeAndScopeIdAndIsActiveTrue(SCOPE_TYPE, SCOPE_ID)).willReturn(3);

            given(bookingRepository.countByScopeAndStatus(SCOPE_TYPE, SCOPE_ID, BookingStatus.COMPLETED)).willReturn(10L);
            given(bookingRepository.countByScopeAndStatus(SCOPE_TYPE, SCOPE_ID, BookingStatus.CANCELLED)).willReturn(2L);
            given(bookingRepository.countByScopeAndStatus(SCOPE_TYPE, SCOPE_ID, BookingStatus.NO_SHOW)).willReturn(1L);
            given(bookingRepository.countByScopeAndStatus(SCOPE_TYPE, SCOPE_ID, BookingStatus.PENDING_APPROVAL)).willReturn(3L);
            given(bookingRepository.countByScopeAndStatus(SCOPE_TYPE, SCOPE_ID, BookingStatus.CONFIRMED)).willReturn(4L);
            given(bookingRepository.countByScopeAndStatus(SCOPE_TYPE, SCOPE_ID, BookingStatus.CHECKED_IN)).willReturn(0L);
            given(bookingRepository.countByScopeAndStatus(SCOPE_TYPE, SCOPE_ID, BookingStatus.REJECTED)).willReturn(1L);

            given(dailyStatsRepository.sumRevenueTotal(SCOPE_TYPE, SCOPE_ID)).willReturn(BigDecimal.valueOf(50000));
            given(dailyStatsRepository.sumPlatformFeeTotal(SCOPE_TYPE, SCOPE_ID)).willReturn(BigDecimal.valueOf(2500));

            // When
            FacilityStatsResponse result = statsService.getStats(SCOPE_TYPE, SCOPE_ID);

            // Then
            assertThat(result.getTotalFacilities()).isEqualTo(5);
            assertThat(result.getActiveFacilities()).isEqualTo(3);
            assertThat(result.getTotalBookings()).isEqualTo(21L);
            assertThat(result.getCompletedBookings()).isEqualTo(10L);
            assertThat(result.getCancelledBookings()).isEqualTo(2L);
            assertThat(result.getNoShowBookings()).isEqualTo(1L);
            assertThat(result.getTotalRevenue()).isEqualByComparingTo(BigDecimal.valueOf(50000));
            assertThat(result.getTotalPlatformFee()).isEqualByComparingTo(BigDecimal.valueOf(2500));
        }

        @Test
        @DisplayName("正常系: データがない場合はゼロが返る")
        void 統計取得_データなし_ゼロが返る() {
            // Given
            given(facilityRepository.countByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID)).willReturn(0);
            given(facilityRepository.countByScopeTypeAndScopeIdAndIsActiveTrue(SCOPE_TYPE, SCOPE_ID)).willReturn(0);

            given(bookingRepository.countByScopeAndStatus(SCOPE_TYPE, SCOPE_ID, BookingStatus.COMPLETED)).willReturn(0L);
            given(bookingRepository.countByScopeAndStatus(SCOPE_TYPE, SCOPE_ID, BookingStatus.CANCELLED)).willReturn(0L);
            given(bookingRepository.countByScopeAndStatus(SCOPE_TYPE, SCOPE_ID, BookingStatus.NO_SHOW)).willReturn(0L);
            given(bookingRepository.countByScopeAndStatus(SCOPE_TYPE, SCOPE_ID, BookingStatus.PENDING_APPROVAL)).willReturn(0L);
            given(bookingRepository.countByScopeAndStatus(SCOPE_TYPE, SCOPE_ID, BookingStatus.CONFIRMED)).willReturn(0L);
            given(bookingRepository.countByScopeAndStatus(SCOPE_TYPE, SCOPE_ID, BookingStatus.CHECKED_IN)).willReturn(0L);
            given(bookingRepository.countByScopeAndStatus(SCOPE_TYPE, SCOPE_ID, BookingStatus.REJECTED)).willReturn(0L);

            given(dailyStatsRepository.sumRevenueTotal(SCOPE_TYPE, SCOPE_ID)).willReturn(null);
            given(dailyStatsRepository.sumPlatformFeeTotal(SCOPE_TYPE, SCOPE_ID)).willReturn(null);

            // When
            FacilityStatsResponse result = statsService.getStats(SCOPE_TYPE, SCOPE_ID);

            // Then
            assertThat(result.getTotalFacilities()).isEqualTo(0);
            assertThat(result.getActiveFacilities()).isEqualTo(0);
            assertThat(result.getTotalBookings()).isEqualTo(0L);
            assertThat(result.getCompletedBookings()).isEqualTo(0L);
            assertThat(result.getCancelledBookings()).isEqualTo(0L);
            assertThat(result.getNoShowBookings()).isEqualTo(0L);
            assertThat(result.getTotalRevenue()).isNull();
            assertThat(result.getTotalPlatformFee()).isNull();
        }

        @Test
        @DisplayName("境界値: 全予約がCOMPLETEDの場合のtotalBookings計算")
        void 統計取得_全件完了_totalBookingsが正しい() {
            // Given
            given(facilityRepository.countByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID)).willReturn(1);
            given(facilityRepository.countByScopeTypeAndScopeIdAndIsActiveTrue(SCOPE_TYPE, SCOPE_ID)).willReturn(1);

            given(bookingRepository.countByScopeAndStatus(SCOPE_TYPE, SCOPE_ID, BookingStatus.COMPLETED)).willReturn(100L);
            given(bookingRepository.countByScopeAndStatus(SCOPE_TYPE, SCOPE_ID, BookingStatus.CANCELLED)).willReturn(0L);
            given(bookingRepository.countByScopeAndStatus(SCOPE_TYPE, SCOPE_ID, BookingStatus.NO_SHOW)).willReturn(0L);
            given(bookingRepository.countByScopeAndStatus(SCOPE_TYPE, SCOPE_ID, BookingStatus.PENDING_APPROVAL)).willReturn(0L);
            given(bookingRepository.countByScopeAndStatus(SCOPE_TYPE, SCOPE_ID, BookingStatus.CONFIRMED)).willReturn(0L);
            given(bookingRepository.countByScopeAndStatus(SCOPE_TYPE, SCOPE_ID, BookingStatus.CHECKED_IN)).willReturn(0L);
            given(bookingRepository.countByScopeAndStatus(SCOPE_TYPE, SCOPE_ID, BookingStatus.REJECTED)).willReturn(0L);

            given(dailyStatsRepository.sumRevenueTotal(SCOPE_TYPE, SCOPE_ID)).willReturn(BigDecimal.valueOf(100000));
            given(dailyStatsRepository.sumPlatformFeeTotal(SCOPE_TYPE, SCOPE_ID)).willReturn(BigDecimal.valueOf(5000));

            // When
            FacilityStatsResponse result = statsService.getStats(SCOPE_TYPE, SCOPE_ID);

            // Then
            assertThat(result.getTotalBookings()).isEqualTo(100L);
            assertThat(result.getCompletedBookings()).isEqualTo(100L);
        }
    }
}
