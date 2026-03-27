package com.mannschaft.app.facility;

import com.mannschaft.app.facility.dto.BookingDetailResponse;
import com.mannschaft.app.facility.dto.BookingPaymentResponse;
import com.mannschaft.app.facility.dto.BookingResponse;
import com.mannschaft.app.facility.dto.CalendarBookingResponse;
import com.mannschaft.app.facility.dto.EquipmentResponse;
import com.mannschaft.app.facility.dto.FacilityDetailResponse;
import com.mannschaft.app.facility.dto.FacilityResponse;
import com.mannschaft.app.facility.dto.FacilitySettingsResponse;
import com.mannschaft.app.facility.dto.TimeRateResponse;
import com.mannschaft.app.facility.dto.UsageRuleResponse;
import com.mannschaft.app.facility.entity.FacilityBookingEntity;
import com.mannschaft.app.facility.entity.FacilityBookingPaymentEntity;
import com.mannschaft.app.facility.entity.FacilityEquipmentEntity;
import com.mannschaft.app.facility.entity.FacilitySettingsEntity;
import com.mannschaft.app.facility.entity.FacilityTimeRateEntity;
import com.mannschaft.app.facility.entity.FacilityUsageRuleEntity;
import com.mannschaft.app.facility.entity.SharedFacilityEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FacilityMapper} (MapStruct生成実装) の単体テスト。
 * FacilityMapperImpl を直接インスタンス化してマッピングを検証する。
 */
@DisplayName("FacilityMapper 単体テスト")
class FacilityMapperTest {

    private FacilityMapperImpl mapper;

    @BeforeEach
    void setUp() {
        mapper = new FacilityMapperImpl();
    }

    // ----------------------------------------
    // Helper: BaseEntity の id を Reflection でセット
    // ----------------------------------------
    private void setId(Object entity, Long id) throws Exception {
        Field field = entity.getClass().getSuperclass().getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }

    private void setCreatedAt(Object entity, LocalDateTime dt) throws Exception {
        Field field = entity.getClass().getSuperclass().getDeclaredField("createdAt");
        field.setAccessible(true);
        field.set(entity, dt);
    }

    private void setUpdatedAt(Object entity, LocalDateTime dt) throws Exception {
        Field field = entity.getClass().getSuperclass().getDeclaredField("updatedAt");
        field.setAccessible(true);
        field.set(entity, dt);
    }

    // ----------------------------------------
    // SharedFacilityEntity → FacilityResponse
    // ----------------------------------------
    @Nested
    @DisplayName("toFacilityResponse")
    class ToFacilityResponse {

        @Test
        @DisplayName("正常系: facilityType が name() に変換される")
        void facilityType名前変換() throws Exception {
            SharedFacilityEntity entity = SharedFacilityEntity.builder()
                    .scopeType("TEAM").scopeId(10L).name("会議室A")
                    .facilityType(FacilityType.MEETING_ROOM).capacity(10)
                    .autoApprove(false).isActive(true).displayOrder(1)
                    .cleaningBufferMinutes(0).createdBy(1L)
                    .ratePerSlot(BigDecimal.valueOf(500))
                    .ratePerNight(null)
                    .build();
            setId(entity, 1L);
            LocalDateTime now = LocalDateTime.of(2026, 1, 1, 10, 0);
            setCreatedAt(entity, now);

            FacilityResponse response = mapper.toFacilityResponse(entity);

            assertThat(response.getId()).isEqualTo(1L);
            assertThat(response.getName()).isEqualTo("会議室A");
            assertThat(response.getFacilityType()).isEqualTo("MEETING_ROOM");
            assertThat(response.getScopeType()).isEqualTo("TEAM");
            assertThat(response.getScopeId()).isEqualTo(10L);
            assertThat(response.getCapacity()).isEqualTo(10);
            assertThat(response.getRatePerSlot()).isEqualByComparingTo(BigDecimal.valueOf(500));
        }

        @Test
        @DisplayName("正常系: リスト変換")
        void リスト変換() throws Exception {
            SharedFacilityEntity e1 = SharedFacilityEntity.builder()
                    .scopeType("TEAM").scopeId(1L).name("A").facilityType(FacilityType.FITNESS_ROOM)
                    .capacity(5).autoApprove(false).isActive(true).displayOrder(0)
                    .cleaningBufferMinutes(0).createdBy(1L).build();
            SharedFacilityEntity e2 = SharedFacilityEntity.builder()
                    .scopeType("TEAM").scopeId(1L).name("B").facilityType(FacilityType.STUDY_ROOM)
                    .capacity(3).autoApprove(true).isActive(true).displayOrder(1)
                    .cleaningBufferMinutes(0).createdBy(1L).build();
            setId(e1, 1L);
            setId(e2, 2L);

            List<FacilityResponse> responses = mapper.toFacilityResponseList(List.of(e1, e2));

            assertThat(responses).hasSize(2);
            assertThat(responses.get(0).getFacilityType()).isEqualTo("FITNESS_ROOM");
            assertThat(responses.get(1).getFacilityType()).isEqualTo("STUDY_ROOM");
        }
    }

    // ----------------------------------------
    // SharedFacilityEntity → FacilityDetailResponse
    // ----------------------------------------
    @Nested
    @DisplayName("toFacilityDetailResponse")
    class ToFacilityDetailResponse {

        @Test
        @DisplayName("正常系: 詳細レスポンスへの変換")
        void 詳細レスポンス変換() throws Exception {
            SharedFacilityEntity entity = SharedFacilityEntity.builder()
                    .scopeType("ORGANIZATION").scopeId(20L).name("大ホール")
                    .facilityType(FacilityType.MULTIPURPOSE_HALL).capacity(200)
                    .floor("3F").locationDetail("本館3階").description("広いホール")
                    .ratePerSlot(BigDecimal.valueOf(1000)).ratePerNight(null)
                    .checkInTime(LocalTime.of(9, 0)).checkOutTime(LocalTime.of(22, 0))
                    .cleaningBufferMinutes(30).autoApprove(false).isActive(true)
                    .displayOrder(2).createdBy(5L).build();
            setId(entity, 10L);
            LocalDateTime now = LocalDateTime.of(2026, 3, 1, 8, 0);
            setCreatedAt(entity, now);
            setUpdatedAt(entity, now);

            FacilityDetailResponse response = mapper.toFacilityDetailResponse(entity);

            assertThat(response.getId()).isEqualTo(10L);
            assertThat(response.getFacilityType()).isEqualTo("MULTIPURPOSE_HALL");
            assertThat(response.getCapacity()).isEqualTo(200);
            assertThat(response.getFloor()).isEqualTo("3F");
            assertThat(response.getDescription()).isEqualTo("広いホール");
            assertThat(response.getCleaningBufferMinutes()).isEqualTo(30);
            // imageUrls は ignore なので null のはず
            assertThat(response.getImageUrls()).isNull();
        }
    }

    // ----------------------------------------
    // FacilityUsageRuleEntity → UsageRuleResponse
    // ----------------------------------------
    @Nested
    @DisplayName("toUsageRuleResponse")
    class ToUsageRuleResponse {

        @Test
        @DisplayName("正常系: 利用ルールの変換")
        void 利用ルール変換() throws Exception {
            FacilityUsageRuleEntity entity = FacilityUsageRuleEntity.builder()
                    .facilityId(1L)
                    .maxHoursPerBooking(new BigDecimal("4.0"))
                    .minHoursPerBooking(new BigDecimal("0.5"))
                    .maxBookingsPerMonthPerUser(4)
                    .maxConsecutiveSlots(8)
                    .minAdvanceHours(1)
                    .maxAdvanceDays(30)
                    .maxStayNights(0)
                    .cancellationDeadlineHours(24)
                    .availableTimeFrom(LocalTime.of(9, 0))
                    .availableTimeTo(LocalTime.of(22, 0))
                    .availableDaysOfWeek("[0,1,2,3,4,5,6]")
                    .build();
            setId(entity, 5L);

            UsageRuleResponse response = mapper.toUsageRuleResponse(entity);

            assertThat(response.getId()).isEqualTo(5L);
            assertThat(response.getFacilityId()).isEqualTo(1L);
            assertThat(response.getMaxHoursPerBooking()).isEqualByComparingTo(new BigDecimal("4.0"));
            assertThat(response.getCancellationDeadlineHours()).isEqualTo(24);
        }
    }

    // ----------------------------------------
    // FacilityTimeRateEntity → TimeRateResponse
    // ----------------------------------------
    @Nested
    @DisplayName("toTimeRateResponse")
    class ToTimeRateResponse {

        @Test
        @DisplayName("正常系: dayType が name() に変換される")
        void dayType名前変換() throws Exception {
            FacilityTimeRateEntity entity = FacilityTimeRateEntity.builder()
                    .facilityId(1L).dayType(DayType.WEEKEND)
                    .timeFrom(LocalTime.of(9, 0)).timeTo(LocalTime.of(18, 0))
                    .ratePerSlot(BigDecimal.valueOf(1000)).build();
            setId(entity, 3L);

            TimeRateResponse response = mapper.toTimeRateResponse(entity);

            assertThat(response.getId()).isEqualTo(3L);
            assertThat(response.getDayType()).isEqualTo("WEEKEND");
            assertThat(response.getRatePerSlot()).isEqualByComparingTo(BigDecimal.valueOf(1000));
        }

        @Test
        @DisplayName("正常系: HOLIDAY タイプ")
        void HOLIDAYタイプ変換() throws Exception {
            FacilityTimeRateEntity entity = FacilityTimeRateEntity.builder()
                    .facilityId(1L).dayType(DayType.HOLIDAY)
                    .timeFrom(LocalTime.of(10, 0)).timeTo(LocalTime.of(20, 0))
                    .ratePerSlot(BigDecimal.valueOf(1500)).build();
            setId(entity, 4L);

            TimeRateResponse response = mapper.toTimeRateResponse(entity);
            assertThat(response.getDayType()).isEqualTo("HOLIDAY");

            // リスト変換
            List<TimeRateResponse> list = mapper.toTimeRateResponseList(List.of(entity));
            assertThat(list).hasSize(1);
            assertThat(list.get(0).getDayType()).isEqualTo("HOLIDAY");
        }
    }

    // ----------------------------------------
    // FacilityEquipmentEntity → EquipmentResponse
    // ----------------------------------------
    @Nested
    @DisplayName("toEquipmentResponse")
    class ToEquipmentResponse {

        @Test
        @DisplayName("正常系: 備品レスポンスの変換")
        void 備品レスポンス変換() throws Exception {
            FacilityEquipmentEntity entity = FacilityEquipmentEntity.builder()
                    .facilityId(1L).name("プロジェクター").description("4K対応")
                    .totalQuantity(2).pricePerUse(BigDecimal.valueOf(500))
                    .isAvailable(true).displayOrder(1).build();
            setId(entity, 7L);
            LocalDateTime now = LocalDateTime.of(2026, 2, 1, 0, 0);
            setCreatedAt(entity, now);
            setUpdatedAt(entity, now);

            EquipmentResponse response = mapper.toEquipmentResponse(entity);

            assertThat(response.getId()).isEqualTo(7L);
            assertThat(response.getName()).isEqualTo("プロジェクター");
            assertThat(response.getTotalQuantity()).isEqualTo(2);
            assertThat(response.getIsAvailable()).isTrue();

            // リスト変換
            List<EquipmentResponse> list = mapper.toEquipmentResponseList(List.of(entity));
            assertThat(list).hasSize(1);
        }
    }

    // ----------------------------------------
    // FacilityBookingEntity → BookingResponse
    // ----------------------------------------
    @Nested
    @DisplayName("toBookingResponse")
    class ToBookingResponse {

        @Test
        @DisplayName("正常系: status が name() に変換される")
        void status名前変換() throws Exception {
            FacilityBookingEntity entity = FacilityBookingEntity.builder()
                    .facilityId(1L).bookedBy(2L)
                    .bookingDate(LocalDate.of(2026, 4, 10))
                    .timeFrom(LocalTime.of(10, 0)).timeTo(LocalTime.of(12, 0))
                    .slotCount(4).purpose("打ち合わせ")
                    .usageFee(BigDecimal.valueOf(2000))
                    .equipmentFee(BigDecimal.ZERO)
                    .totalFee(BigDecimal.valueOf(2000))
                    .status(BookingStatus.CONFIRMED)
                    .stayNights(0).build();
            setId(entity, 100L);

            BookingResponse response = mapper.toBookingResponse(entity);

            assertThat(response.getId()).isEqualTo(100L);
            assertThat(response.getStatus()).isEqualTo("CONFIRMED");
            assertThat(response.getFacilityName()).isNull(); // ignore
            assertThat(response.getTotalFee()).isEqualByComparingTo(BigDecimal.valueOf(2000));
        }

        @Test
        @DisplayName("正常系: PENDING_APPROVAL ステータス")
        void PENDING_APPROVALステータス() throws Exception {
            FacilityBookingEntity entity = FacilityBookingEntity.builder()
                    .facilityId(1L).bookedBy(3L)
                    .bookingDate(LocalDate.of(2026, 5, 1))
                    .timeFrom(LocalTime.of(9, 0)).timeTo(LocalTime.of(10, 0))
                    .slotCount(2)
                    .usageFee(BigDecimal.valueOf(1000))
                    .equipmentFee(BigDecimal.ZERO)
                    .totalFee(BigDecimal.valueOf(1000))
                    .status(BookingStatus.PENDING_APPROVAL)
                    .stayNights(0).build();
            setId(entity, 101L);

            BookingResponse response = mapper.toBookingResponse(entity);
            assertThat(response.getStatus()).isEqualTo("PENDING_APPROVAL");
        }
    }

    // ----------------------------------------
    // FacilityBookingEntity → BookingDetailResponse
    // ----------------------------------------
    @Nested
    @DisplayName("toBookingDetailResponse")
    class ToBookingDetailResponse {

        @Test
        @DisplayName("正常系: 詳細レスポンス変換")
        void 詳細レスポンス変換() throws Exception {
            FacilityBookingEntity entity = FacilityBookingEntity.builder()
                    .facilityId(2L).bookedBy(5L)
                    .bookingDate(LocalDate.of(2026, 4, 15))
                    .timeFrom(LocalTime.of(13, 0)).timeTo(LocalTime.of(15, 0))
                    .slotCount(4).purpose("研修").attendeeCount(20)
                    .usageFee(BigDecimal.valueOf(3000))
                    .equipmentFee(BigDecimal.valueOf(500))
                    .totalFee(BigDecimal.valueOf(3500))
                    .status(BookingStatus.CHECKED_IN)
                    .stayNights(0).build();
            setId(entity, 200L);

            BookingDetailResponse response = mapper.toBookingDetailResponse(entity);

            assertThat(response.getId()).isEqualTo(200L);
            assertThat(response.getStatus()).isEqualTo("CHECKED_IN");
            assertThat(response.getAttendeeCount()).isEqualTo(20);
            assertThat(response.getEquipment()).isNull(); // ignore
            assertThat(response.getFacilityName()).isNull(); // ignore
        }
    }

    // ----------------------------------------
    // FacilityBookingEntity → CalendarBookingResponse
    // ----------------------------------------
    @Nested
    @DisplayName("toCalendarBookingResponse")
    class ToCalendarBookingResponse {

        @Test
        @DisplayName("正常系: カレンダー予約レスポンス変換")
        void カレンダー予約レスポンス変換() throws Exception {
            FacilityBookingEntity entity = FacilityBookingEntity.builder()
                    .facilityId(3L).bookedBy(6L)
                    .bookingDate(LocalDate.of(2026, 6, 1))
                    .checkOutDate(LocalDate.of(2026, 6, 3))
                    .timeFrom(LocalTime.of(15, 0)).timeTo(LocalTime.of(11, 0))
                    .slotCount(1).stayNights(2)
                    .usageFee(BigDecimal.valueOf(20000))
                    .equipmentFee(BigDecimal.ZERO).totalFee(BigDecimal.valueOf(20000))
                    .status(BookingStatus.COMPLETED).build();
            setId(entity, 300L);

            CalendarBookingResponse response = mapper.toCalendarBookingResponse(entity);

            assertThat(response.getId()).isEqualTo(300L);
            assertThat(response.getStatus()).isEqualTo("COMPLETED");
            assertThat(response.getFacilityName()).isNull();
            assertThat(response.getBookingDate()).isEqualTo(LocalDate.of(2026, 6, 1));

            // リスト変換
            List<CalendarBookingResponse> list = mapper.toCalendarBookingResponseList(List.of(entity));
            assertThat(list).hasSize(1);
        }
    }

    // ----------------------------------------
    // FacilityBookingPaymentEntity → BookingPaymentResponse
    // ----------------------------------------
    @Nested
    @DisplayName("toBookingPaymentResponse")
    class ToBookingPaymentResponse {

        @Test
        @DisplayName("正常系: 支払いレスポンス変換")
        void 支払いレスポンス変換() throws Exception {
            FacilityBookingPaymentEntity entity = FacilityBookingPaymentEntity.builder()
                    .bookingId(100L).payerUserId(5L)
                    .paymentMethod(PaymentMethod.DIRECT)
                    .amount(BigDecimal.valueOf(3500))
                    .status(PaymentStatus.SUCCEEDED)
                    .build();
            setId(entity, 50L);
            LocalDateTime paidAt = LocalDateTime.of(2026, 4, 10, 12, 0);
            // paidAt は builder に含まれないので直接フィールドアクセスは不要 — status だけ検証
            LocalDateTime now = LocalDateTime.of(2026, 4, 10, 0, 0);
            setCreatedAt(entity, now);
            setUpdatedAt(entity, now);

            BookingPaymentResponse response = mapper.toBookingPaymentResponse(entity);

            assertThat(response.getId()).isEqualTo(50L);
            assertThat(response.getPaymentMethod()).isEqualTo("DIRECT");
            assertThat(response.getStatus()).isEqualTo("SUCCEEDED");
            assertThat(response.getAmount()).isEqualByComparingTo(BigDecimal.valueOf(3500));
        }
    }

    // ----------------------------------------
    // FacilitySettingsEntity → FacilitySettingsResponse
    // ----------------------------------------
    @Nested
    @DisplayName("toSettingsResponse")
    class ToSettingsResponse {

        @Test
        @DisplayName("正常系: 設定レスポンス変換")
        void 設定レスポンス変換() throws Exception {
            FacilitySettingsEntity entity = FacilitySettingsEntity.builder()
                    .scopeType("TEAM").scopeId(1L)
                    .requiresApproval(true)
                    .maxBookingsPerDayPerUser(3)
                    .allowStripePayment(false)
                    .cancellationDeadlineHours(48)
                    .noShowPenaltyEnabled(true)
                    .noShowPenaltyThreshold(3)
                    .noShowPenaltyDays(30)
                    .build();
            setId(entity, 1L);
            LocalDateTime now = LocalDateTime.of(2026, 1, 1, 0, 0);
            setCreatedAt(entity, now);
            setUpdatedAt(entity, now);

            FacilitySettingsResponse response = mapper.toSettingsResponse(entity);

            assertThat(response.getId()).isEqualTo(1L);
            assertThat(response.getScopeType()).isEqualTo("TEAM");
            assertThat(response.getRequiresApproval()).isTrue();
            assertThat(response.getMaxBookingsPerDayPerUser()).isEqualTo(3);
            assertThat(response.getCancellationDeadlineHours()).isEqualTo(48);
            assertThat(response.getNoShowPenaltyEnabled()).isTrue();
        }
    }
}
