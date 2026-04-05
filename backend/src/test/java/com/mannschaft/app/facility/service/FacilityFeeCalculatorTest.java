package com.mannschaft.app.facility.service;

import com.mannschaft.app.common.HolidayService;
import com.mannschaft.app.facility.DayType;
import com.mannschaft.app.facility.FacilityType;
import com.mannschaft.app.facility.entity.FacilityBookingEquipmentEntity;
import com.mannschaft.app.facility.entity.FacilityTimeRateEntity;
import com.mannschaft.app.facility.entity.SharedFacilityEntity;
import com.mannschaft.app.facility.repository.FacilityTimeRateRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * {@link FacilityFeeCalculator} の単体テスト。
 * 利用料金・備品料金・スロット数計算を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FacilityFeeCalculator 単体テスト")
class FacilityFeeCalculatorTest {

    @Mock
    private FacilityTimeRateRepository timeRateRepository;

    @Mock
    private HolidayService holidayService;

    @InjectMocks
    private FacilityFeeCalculator feeCalculator;

    private static final Long FACILITY_ID = 1L;

    private SharedFacilityEntity createFacilityWithRate(BigDecimal ratePerSlot) {
        return SharedFacilityEntity.builder()
                .scopeType("TEAM")
                .scopeId(1L)
                .name("会議室A")
                .facilityType(FacilityType.MEETING_ROOM)
                .capacity(10)
                .isActive(true)
                .autoApprove(false)
                .cleaningBufferMinutes(0)
                .ratePerSlot(ratePerSlot)
                .displayOrder(0)
                .createdBy(1L)
                .build();
    }

    // ========================================
    // calculateSlotCount
    // ========================================

    @Nested
    @DisplayName("calculateSlotCount")
    class CalculateSlotCount {

        @Test
        @DisplayName("正常系: 60分は2スロット")
        void スロット数計算_60分_2スロット() {
            // When
            int result = feeCalculator.calculateSlotCount(LocalTime.of(10, 0), LocalTime.of(11, 0));

            // Then
            assertThat(result).isEqualTo(2);
        }

        @Test
        @DisplayName("正常系: 90分は3スロット")
        void スロット数計算_90分_3スロット() {
            // When
            int result = feeCalculator.calculateSlotCount(LocalTime.of(9, 0), LocalTime.of(10, 30));

            // Then
            assertThat(result).isEqualTo(3);
        }

        @Test
        @DisplayName("正常系: 30分は1スロット")
        void スロット数計算_30分_1スロット() {
            // When
            int result = feeCalculator.calculateSlotCount(LocalTime.of(9, 0), LocalTime.of(9, 30));

            // Then
            assertThat(result).isEqualTo(1);
        }

        @Test
        @DisplayName("境界値: 0分（同時刻）は最小1スロット")
        void スロット数計算_0分_最小1スロット() {
            // When
            int result = feeCalculator.calculateSlotCount(LocalTime.of(10, 0), LocalTime.of(10, 0));

            // Then
            assertThat(result).isEqualTo(1);
        }

        @Test
        @DisplayName("正常系: 120分は4スロット")
        void スロット数計算_120分_4スロット() {
            // When
            int result = feeCalculator.calculateSlotCount(LocalTime.of(10, 0), LocalTime.of(12, 0));

            // Then
            assertThat(result).isEqualTo(4);
        }
    }

    // ========================================
    // calculateEquipmentFee
    // ========================================

    @Nested
    @DisplayName("calculateEquipmentFee")
    class CalculateEquipmentFee {

        @Test
        @DisplayName("正常系: 備品料金の合計が返る")
        void 備品料金計算_正常_合計が返る() {
            // Given
            FacilityBookingEquipmentEntity e1 = FacilityBookingEquipmentEntity.builder()
                    .bookingId(1L).equipmentId(1L).quantity(2)
                    .unitPrice(BigDecimal.valueOf(300)).subtotal(BigDecimal.valueOf(600)).build();
            FacilityBookingEquipmentEntity e2 = FacilityBookingEquipmentEntity.builder()
                    .bookingId(1L).equipmentId(2L).quantity(1)
                    .unitPrice(BigDecimal.valueOf(500)).subtotal(BigDecimal.valueOf(500)).build();

            // When
            BigDecimal result = feeCalculator.calculateEquipmentFee(List.of(e1, e2));

            // Then
            assertThat(result).isEqualByComparingTo(BigDecimal.valueOf(1100));
        }

        @Test
        @DisplayName("正常系: 備品がない場合はゼロ")
        void 備品料金計算_備品なし_ゼロ() {
            // When
            BigDecimal result = feeCalculator.calculateEquipmentFee(List.of());

            // Then
            assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    // ========================================
    // calculateUsageFee
    // ========================================

    @Nested
    @DisplayName("calculateUsageFee")
    class CalculateUsageFee {

        @Test
        @DisplayName("正常系: 宿泊がある場合は宿泊料金×宿泊数")
        void 料金計算_宿泊あり_宿泊料金が適用される() {
            // Given
            SharedFacilityEntity facility = SharedFacilityEntity.builder()
                    .scopeType("TEAM").scopeId(1L).name("宿泊施設")
                    .facilityType(FacilityType.GUEST_ROOM)
                    .capacity(10).isActive(true).autoApprove(false)
                    .cleaningBufferMinutes(0).displayOrder(0).createdBy(1L)
                    .ratePerSlot(BigDecimal.valueOf(500))
                    .ratePerNight(BigDecimal.valueOf(10000))
                    .build();
            LocalDate weekday = LocalDate.of(2026, 4, 6); // 月曜日

            // When — 宿泊ありの場合は即 ratePerNight で計算されるため holiday/timeRate スタブ不要
            BigDecimal result = feeCalculator.calculateUsageFee(
                    facility, weekday, LocalTime.of(15, 0), LocalTime.of(11, 0), 2);

            // Then
            assertThat(result).isEqualByComparingTo(BigDecimal.valueOf(20000)); // 10000 * 2
        }

        @Test
        @DisplayName("正常系: 時間帯別料金がある場合はそちらが使用される")
        void 料金計算_時間帯別料金あり_時間帯料金が適用される() {
            // Given
            SharedFacilityEntity facility = createFacilityWithRate(BigDecimal.valueOf(500));
            LocalDate weekday = LocalDate.of(2026, 4, 6); // 月曜日

            FacilityTimeRateEntity rate = FacilityTimeRateEntity.builder()
                    .facilityId(FACILITY_ID)
                    .dayType(DayType.WEEKDAY)
                    .timeFrom(LocalTime.of(9, 0))
                    .timeTo(LocalTime.of(18, 0))
                    .ratePerSlot(BigDecimal.valueOf(800))
                    .build();

            given(holidayService.isSystemHoliday(weekday)).willReturn(false);
            given(timeRateRepository.findByFacilityIdAndDayType(facility.getId(), DayType.WEEKDAY))
                    .willReturn(List.of(rate));

            // When: 10:00-11:00（2スロット）
            BigDecimal result = feeCalculator.calculateUsageFee(
                    facility, weekday, LocalTime.of(10, 0), LocalTime.of(11, 0), 0);

            // Then: 800 * 2スロット
            assertThat(result).isEqualByComparingTo(BigDecimal.valueOf(1600));
        }

        @Test
        @DisplayName("正常系: デフォルト料金（ratePerSlot x スロット数）")
        void 料金計算_デフォルト料金_スロット数x単価() {
            // Given
            SharedFacilityEntity facility = createFacilityWithRate(BigDecimal.valueOf(500));
            LocalDate weekday = LocalDate.of(2026, 4, 6); // 月曜日

            given(holidayService.isSystemHoliday(weekday)).willReturn(false);
            given(timeRateRepository.findByFacilityIdAndDayType(facility.getId(), DayType.WEEKDAY))
                    .willReturn(List.of());

            // When: 10:00-12:00（4スロット）
            BigDecimal result = feeCalculator.calculateUsageFee(
                    facility, weekday, LocalTime.of(10, 0), LocalTime.of(12, 0), 0);

            // Then: 500 * 4 = 2000
            assertThat(result).isEqualByComparingTo(BigDecimal.valueOf(2000));
        }

        @Test
        @DisplayName("正常系: ratePerSlotがnullの場合はゼロ")
        void 料金計算_単価null_ゼロ() {
            // Given
            SharedFacilityEntity facility = createFacilityWithRate(null);
            LocalDate weekday = LocalDate.of(2026, 4, 6); // 月曜日

            given(holidayService.isSystemHoliday(weekday)).willReturn(false);
            given(timeRateRepository.findByFacilityIdAndDayType(facility.getId(), DayType.WEEKDAY))
                    .willReturn(List.of());

            // When
            BigDecimal result = feeCalculator.calculateUsageFee(
                    facility, weekday, LocalTime.of(10, 0), LocalTime.of(12, 0), 0);

            // Then
            assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("正常系: 週末は WEEKEND の DayType が使われる")
        void 料金計算_週末_WEEKENDタイプが適用される() {
            // Given
            SharedFacilityEntity facility = createFacilityWithRate(BigDecimal.valueOf(500));
            LocalDate saturday = LocalDate.of(2026, 4, 4); // 土曜日

            FacilityTimeRateEntity rate = FacilityTimeRateEntity.builder()
                    .facilityId(FACILITY_ID)
                    .dayType(DayType.WEEKEND)
                    .timeFrom(LocalTime.of(9, 0))
                    .timeTo(LocalTime.of(18, 0))
                    .ratePerSlot(BigDecimal.valueOf(1000))
                    .build();

            given(holidayService.isSystemHoliday(saturday)).willReturn(false);
            given(timeRateRepository.findByFacilityIdAndDayType(facility.getId(), DayType.WEEKEND))
                    .willReturn(List.of(rate));

            // When: 10:00-11:00（2スロット）
            BigDecimal result = feeCalculator.calculateUsageFee(
                    facility, saturday, LocalTime.of(10, 0), LocalTime.of(11, 0), 0);

            // Then: 1000 * 2 = 2000
            assertThat(result).isEqualByComparingTo(BigDecimal.valueOf(2000));
        }

        @Test
        @DisplayName("正常系: 祝日は HOLIDAY の DayType が使われる")
        void 料金計算_祝日_HOLIDAYタイプが適用される() {
            // Given
            SharedFacilityEntity facility = createFacilityWithRate(BigDecimal.valueOf(500));
            LocalDate holiday = LocalDate.of(2026, 4, 6); // 月曜日だが祝日とみなす

            FacilityTimeRateEntity rate = FacilityTimeRateEntity.builder()
                    .facilityId(FACILITY_ID)
                    .dayType(DayType.HOLIDAY)
                    .timeFrom(LocalTime.of(9, 0))
                    .timeTo(LocalTime.of(18, 0))
                    .ratePerSlot(BigDecimal.valueOf(1200))
                    .build();

            given(holidayService.isSystemHoliday(holiday)).willReturn(true);
            given(timeRateRepository.findByFacilityIdAndDayType(facility.getId(), DayType.HOLIDAY))
                    .willReturn(List.of(rate));

            // When: 10:00-11:00（2スロット）
            BigDecimal result = feeCalculator.calculateUsageFee(
                    facility, holiday, LocalTime.of(10, 0), LocalTime.of(11, 0), 0);

            // Then: 1200 * 2 = 2400
            assertThat(result).isEqualByComparingTo(BigDecimal.valueOf(2400));
        }

        @Test
        @DisplayName("正常系: 時間帯別料金でカバーされない時間帯はゼロ")
        void 料金計算_時間帯外_ゼロが適用される() {
            // Given
            SharedFacilityEntity facility = createFacilityWithRate(null);
            LocalDate weekday = LocalDate.of(2026, 4, 6);

            // 9:00-10:00 だけカバーする料金
            FacilityTimeRateEntity rate = FacilityTimeRateEntity.builder()
                    .facilityId(FACILITY_ID)
                    .dayType(DayType.WEEKDAY)
                    .timeFrom(LocalTime.of(9, 0))
                    .timeTo(LocalTime.of(10, 0))
                    .ratePerSlot(BigDecimal.valueOf(800))
                    .build();

            given(holidayService.isSystemHoliday(weekday)).willReturn(false);
            given(timeRateRepository.findByFacilityIdAndDayType(facility.getId(), DayType.WEEKDAY))
                    .willReturn(List.of(rate));

            // When: 11:00-12:00（料金設定外の時間帯）
            BigDecimal result = feeCalculator.calculateUsageFee(
                    facility, weekday, LocalTime.of(11, 0), LocalTime.of(12, 0), 0);

            // Then: 料金設定外なのでゼロ * 2スロット = 0
            assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }
}
