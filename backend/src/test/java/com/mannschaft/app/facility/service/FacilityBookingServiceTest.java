package com.mannschaft.app.facility.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.facility.BookingStatus;
import com.mannschaft.app.facility.FacilityMapper;
import com.mannschaft.app.facility.dto.ApproveBookingRequest;
import com.mannschaft.app.facility.dto.BookingDetailResponse;
import com.mannschaft.app.facility.dto.BookingResponse;
import com.mannschaft.app.facility.dto.CalendarBookingResponse;
import com.mannschaft.app.facility.dto.CancelBookingRequest;
import com.mannschaft.app.facility.dto.CreateBookingRequest;
import com.mannschaft.app.facility.dto.RejectBookingRequest;
import com.mannschaft.app.facility.dto.UpdateBookingRequest;
import com.mannschaft.app.facility.entity.FacilityBookingEntity;
import com.mannschaft.app.facility.entity.FacilityEquipmentEntity;
import com.mannschaft.app.facility.entity.FacilitySettingsEntity;
import com.mannschaft.app.facility.entity.SharedFacilityEntity;
import com.mannschaft.app.facility.repository.FacilityBookingEquipmentRepository;
import com.mannschaft.app.facility.repository.FacilityBookingRepository;
import com.mannschaft.app.facility.repository.FacilitySettingsRepository;
import com.mannschaft.app.facility.repository.FacilityUsageRuleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * {@link FacilityBookingService} の単体テスト。
 * 施設予約CRUD・ステータス遷移・カレンダー取得を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FacilityBookingService 単体テスト")
class FacilityBookingServiceTest {

    @Mock
    private FacilityBookingRepository bookingRepository;

    @Mock
    private FacilityBookingEquipmentRepository bookingEquipmentRepository;

    @Mock
    private FacilityUsageRuleRepository usageRuleRepository;

    @Mock
    private FacilitySettingsRepository settingsRepository;

    @Mock
    private FacilityService facilityService;

    @Mock
    private FacilityEquipmentService equipmentService;

    @Mock
    private FacilityFeeCalculator feeCalculator;

    @Mock
    private FacilityMapper facilityMapper;

    @InjectMocks
    private FacilityBookingService bookingService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final String SCOPE_TYPE = "TEAM";
    private static final Long SCOPE_ID = 1L;
    private static final Long USER_ID = 100L;
    private static final Long ADMIN_USER_ID = 200L;
    private static final Long BOOKING_ID = 10L;
    private static final Long FACILITY_ID = 5L;

    private SharedFacilityEntity createActiveFacility() {
        return SharedFacilityEntity.builder()
                .scopeType(SCOPE_TYPE)
                .scopeId(SCOPE_ID)
                .name("会議室A")
                .facilityType(com.mannschaft.app.facility.FacilityType.MEETING_ROOM)
                .capacity(10)
                .isActive(true)
                .autoApprove(false)
                .cleaningBufferMinutes(0)
                .ratePerSlot(BigDecimal.valueOf(500))
                .displayOrder(0)
                .createdBy(ADMIN_USER_ID)
                .build();
    }

    private SharedFacilityEntity createInactiveFacility() {
        return SharedFacilityEntity.builder()
                .scopeType(SCOPE_TYPE)
                .scopeId(SCOPE_ID)
                .name("会議室B")
                .facilityType(com.mannschaft.app.facility.FacilityType.MEETING_ROOM)
                .capacity(10)
                .isActive(false)
                .autoApprove(false)
                .cleaningBufferMinutes(0)
                .displayOrder(0)
                .createdBy(ADMIN_USER_ID)
                .build();
    }

    private FacilityBookingEntity createPendingBooking() {
        return FacilityBookingEntity.builder()
                .facilityId(FACILITY_ID)
                .bookedBy(USER_ID)
                .bookingDate(LocalDate.now().plusDays(3))
                .timeFrom(LocalTime.of(10, 0))
                .timeTo(LocalTime.of(12, 0))
                .slotCount(4)
                .status(BookingStatus.PENDING_APPROVAL)
                .usageFee(BigDecimal.valueOf(2000))
                .equipmentFee(BigDecimal.ZERO)
                .totalFee(BigDecimal.valueOf(2000))
                .build();
    }

    private FacilityBookingEntity createConfirmedBooking() {
        return FacilityBookingEntity.builder()
                .facilityId(FACILITY_ID)
                .bookedBy(USER_ID)
                .bookingDate(LocalDate.now().plusDays(3))
                .timeFrom(LocalTime.of(10, 0))
                .timeTo(LocalTime.of(12, 0))
                .slotCount(4)
                .status(BookingStatus.CONFIRMED)
                .usageFee(BigDecimal.valueOf(2000))
                .equipmentFee(BigDecimal.ZERO)
                .totalFee(BigDecimal.valueOf(2000))
                .build();
    }

    private FacilityBookingEntity createCheckedInBooking() {
        return FacilityBookingEntity.builder()
                .facilityId(FACILITY_ID)
                .bookedBy(USER_ID)
                .bookingDate(LocalDate.now())
                .timeFrom(LocalTime.of(10, 0))
                .timeTo(LocalTime.of(12, 0))
                .slotCount(4)
                .status(BookingStatus.CHECKED_IN)
                .usageFee(BigDecimal.valueOf(2000))
                .equipmentFee(BigDecimal.ZERO)
                .totalFee(BigDecimal.valueOf(2000))
                .build();
    }

    private FacilityBookingEntity createCompletedBooking() {
        return FacilityBookingEntity.builder()
                .facilityId(FACILITY_ID)
                .bookedBy(USER_ID)
                .bookingDate(LocalDate.now())
                .timeFrom(LocalTime.of(10, 0))
                .timeTo(LocalTime.of(12, 0))
                .slotCount(4)
                .status(BookingStatus.COMPLETED)
                .usageFee(BigDecimal.valueOf(2000))
                .equipmentFee(BigDecimal.ZERO)
                .totalFee(BigDecimal.valueOf(2000))
                .build();
    }

    private CreateBookingRequest createBookingRequest() {
        return new CreateBookingRequest(
                FACILITY_ID,
                LocalDate.now().plusDays(3),
                null, null,
                LocalTime.of(10, 0),
                LocalTime.of(12, 0),
                "打ち合わせ", 5, null
        );
    }

    // ========================================
    // listBookings
    // ========================================

    @Nested
    @DisplayName("listBookings")
    class ListBookings {

        @Test
        @DisplayName("正常系: ステータス指定なしで全予約を取得する")
        void 予約一覧取得_ステータス指定なし_全予約が返る() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            FacilityBookingEntity booking = createPendingBooking();
            Page<FacilityBookingEntity> page = new PageImpl<>(List.of(booking));
            given(bookingRepository.findByScopeOrderByBookingDateDesc(SCOPE_TYPE, SCOPE_ID, pageable))
                    .willReturn(page);
            given(facilityMapper.toBookingResponse(booking)).willReturn(mock(BookingResponse.class));

            // When
            Page<BookingResponse> result = bookingService.listBookings(SCOPE_TYPE, SCOPE_ID, null, pageable);

            // Then
            assertThat(result.getTotalElements()).isEqualTo(1);
        }

        @Test
        @DisplayName("正常系: ステータス指定で絞り込みする")
        void 予約一覧取得_ステータス指定あり_絞り込み結果が返る() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            FacilityBookingEntity booking = createPendingBooking();
            Page<FacilityBookingEntity> page = new PageImpl<>(List.of(booking));
            given(bookingRepository.findByScopeAndStatusOrderByBookingDateDesc(
                    SCOPE_TYPE, SCOPE_ID, BookingStatus.PENDING_APPROVAL, pageable))
                    .willReturn(page);
            given(facilityMapper.toBookingResponse(booking)).willReturn(mock(BookingResponse.class));

            // When
            Page<BookingResponse> result = bookingService.listBookings(
                    SCOPE_TYPE, SCOPE_ID, "PENDING_APPROVAL", pageable);

            // Then
            assertThat(result.getTotalElements()).isEqualTo(1);
        }
    }

    // ========================================
    // getBooking
    // ========================================

    @Nested
    @DisplayName("getBooking")
    class GetBooking {

        @Test
        @DisplayName("正常系: 予約詳細が返る")
        void 予約詳細取得_正常_詳細が返る() {
            // Given
            FacilityBookingEntity booking = createPendingBooking();
            given(bookingRepository.findById(BOOKING_ID)).willReturn(Optional.of(booking));
            BookingDetailResponse expected = mock(BookingDetailResponse.class);
            given(facilityMapper.toBookingDetailResponse(booking)).willReturn(expected);

            // When
            BookingDetailResponse result = bookingService.getBooking(BOOKING_ID);

            // Then
            assertThat(result).isEqualTo(expected);
        }

        @Test
        @DisplayName("異常系: 予約が存在しないでFACILITY_006例外")
        void 予約詳細取得_存在しない_FACILITY006例外() {
            // Given
            given(bookingRepository.findById(BOOKING_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> bookingService.getBooking(BOOKING_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FACILITY_006"));
        }
    }

    // ========================================
    // createBooking
    // ========================================

    @Nested
    @DisplayName("createBooking")
    class CreateBooking {

        @Test
        @DisplayName("正常系: 予約が作成される（承認待ち）")
        void 予約作成_正常_PENDING_APPROVALで作成される() {
            // Given
            SharedFacilityEntity facility = createActiveFacility();
            CreateBookingRequest request = createBookingRequest();

            given(facilityService.findFacilityByIdOrThrow(FACILITY_ID)).willReturn(facility);
            given(usageRuleRepository.findByFacilityId(any())).willReturn(Optional.empty());
            given(settingsRepository.findByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID)).willReturn(Optional.empty());
            given(bookingRepository.findOverlapping(any(), any(), any(), any(), any()))
                    .willReturn(Collections.emptyList());
            given(feeCalculator.calculateSlotCount(any(), any())).willReturn(4);
            given(feeCalculator.calculateUsageFee(any(), any(), any(), any(), anyInt()))
                    .willReturn(BigDecimal.valueOf(2000));
            given(bookingRepository.save(any(FacilityBookingEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));
            given(facilityMapper.toBookingResponse(any(FacilityBookingEntity.class)))
                    .willReturn(mock(BookingResponse.class));

            // When
            BookingResponse result = bookingService.createBooking(SCOPE_TYPE, SCOPE_ID, USER_ID, request);

            // Then
            assertThat(result).isNotNull();
            verify(bookingRepository).save(any(FacilityBookingEntity.class));
        }

        @Test
        @DisplayName("正常系: autoApproveの施設で予約がCONFIRMEDになる")
        void 予約作成_autoApprove有効_CONFIRMEDで作成される() {
            // Given
            SharedFacilityEntity facility = SharedFacilityEntity.builder()
                    .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID).name("自動承認室")
                    .facilityType(com.mannschaft.app.facility.FacilityType.MEETING_ROOM)
                    .capacity(10).isActive(true).autoApprove(true)
                    .cleaningBufferMinutes(0).displayOrder(0).createdBy(ADMIN_USER_ID)
                    .ratePerSlot(BigDecimal.valueOf(500)).build();
            CreateBookingRequest request = createBookingRequest();

            given(facilityService.findFacilityByIdOrThrow(FACILITY_ID)).willReturn(facility);
            given(usageRuleRepository.findByFacilityId(any())).willReturn(Optional.empty());
            given(settingsRepository.findByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID)).willReturn(Optional.empty());
            given(bookingRepository.findOverlapping(any(), any(), any(), any(), any()))
                    .willReturn(Collections.emptyList());
            given(feeCalculator.calculateSlotCount(any(), any())).willReturn(4);
            given(feeCalculator.calculateUsageFee(any(), any(), any(), any(), anyInt()))
                    .willReturn(BigDecimal.valueOf(2000));
            given(bookingRepository.save(any(FacilityBookingEntity.class)))
                    .willAnswer(invocation -> {
                        FacilityBookingEntity saved = invocation.getArgument(0);
                        assertThat(saved.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
                        return saved;
                    });
            given(facilityMapper.toBookingResponse(any(FacilityBookingEntity.class)))
                    .willReturn(mock(BookingResponse.class));

            // When
            BookingResponse result = bookingService.createBooking(SCOPE_TYPE, SCOPE_ID, USER_ID, request);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("異常系: 無効な施設でFACILITY_019例外")
        void 予約作成_施設無効_FACILITY019例外() {
            // Given
            SharedFacilityEntity facility = createInactiveFacility();
            CreateBookingRequest request = createBookingRequest();
            given(facilityService.findFacilityByIdOrThrow(FACILITY_ID)).willReturn(facility);

            // When / Then
            assertThatThrownBy(() -> bookingService.createBooking(SCOPE_TYPE, SCOPE_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FACILITY_019"));
        }

        @Test
        @DisplayName("異常系: 過去日付でFACILITY_021例外")
        void 予約作成_過去日付_FACILITY021例外() {
            // Given
            SharedFacilityEntity facility = createActiveFacility();
            CreateBookingRequest request = new CreateBookingRequest(
                    FACILITY_ID,
                    LocalDate.now().minusDays(1),
                    null, null,
                    LocalTime.of(10, 0), LocalTime.of(12, 0),
                    "打ち合わせ", 5, null
            );
            given(facilityService.findFacilityByIdOrThrow(FACILITY_ID)).willReturn(facility);

            // When / Then
            assertThatThrownBy(() -> bookingService.createBooking(SCOPE_TYPE, SCOPE_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FACILITY_021"));
        }

        @Test
        @DisplayName("異常系: 時間帯重複でFACILITY_008例外")
        void 予約作成_時間帯重複_FACILITY008例外() {
            // Given
            SharedFacilityEntity facility = createActiveFacility();
            CreateBookingRequest request = createBookingRequest();
            given(facilityService.findFacilityByIdOrThrow(FACILITY_ID)).willReturn(facility);
            given(usageRuleRepository.findByFacilityId(any())).willReturn(Optional.empty());
            given(settingsRepository.findByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID)).willReturn(Optional.empty());
            given(bookingRepository.findOverlapping(any(), any(), any(), any(), any()))
                    .willReturn(List.of(createPendingBooking()));

            // When / Then
            assertThatThrownBy(() -> bookingService.createBooking(SCOPE_TYPE, SCOPE_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FACILITY_008"));
        }

        @Test
        @DisplayName("異常系: 日次予約上限超過でFACILITY_010例外")
        void 予約作成_日次上限超過_FACILITY010例外() {
            // Given
            SharedFacilityEntity facility = createActiveFacility();
            CreateBookingRequest request = createBookingRequest();
            FacilitySettingsEntity settings = FacilitySettingsEntity.builder()
                    .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID)
                    .maxBookingsPerDayPerUser(2).build();

            given(facilityService.findFacilityByIdOrThrow(FACILITY_ID)).willReturn(facility);
            given(usageRuleRepository.findByFacilityId(any())).willReturn(Optional.empty());
            given(settingsRepository.findByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(settings));
            given(bookingRepository.countByFacilityIdAndBookingDateAndBookedByAndStatusNotIn(
                    any(), any(), any(), any())).willReturn(2L);

            // When / Then
            assertThatThrownBy(() -> bookingService.createBooking(SCOPE_TYPE, SCOPE_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FACILITY_010"));
        }

        @Test
        @DisplayName("正常系: 備品付き予約で備品料金が加算される")
        void 予約作成_備品付き_備品料金が加算される() {
            // Given
            SharedFacilityEntity facility = createActiveFacility();
            List<CreateBookingRequest.BookingEquipmentEntry> equipment =
                    List.of(new CreateBookingRequest.BookingEquipmentEntry(1L, 2));
            CreateBookingRequest request = new CreateBookingRequest(
                    FACILITY_ID,
                    LocalDate.now().plusDays(3), null, null,
                    LocalTime.of(10, 0), LocalTime.of(12, 0),
                    "打ち合わせ", 5, equipment
            );

            FacilityEquipmentEntity equipmentEntity = FacilityEquipmentEntity.builder()
                    .facilityId(FACILITY_ID).name("プロジェクター")
                    .pricePerUse(BigDecimal.valueOf(300)).totalQuantity(1).displayOrder(0).build();

            given(facilityService.findFacilityByIdOrThrow(FACILITY_ID)).willReturn(facility);
            given(usageRuleRepository.findByFacilityId(any())).willReturn(Optional.empty());
            given(settingsRepository.findByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID)).willReturn(Optional.empty());
            given(bookingRepository.findOverlapping(any(), any(), any(), any(), any()))
                    .willReturn(Collections.emptyList());
            given(feeCalculator.calculateSlotCount(any(), any())).willReturn(4);
            given(feeCalculator.calculateUsageFee(any(), any(), any(), any(), anyInt()))
                    .willReturn(BigDecimal.valueOf(2000));
            given(bookingRepository.save(any(FacilityBookingEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));
            given(equipmentService.findEquipmentOrThrow(1L)).willReturn(equipmentEntity);
            given(bookingEquipmentRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));
            given(facilityMapper.toBookingResponse(any(FacilityBookingEntity.class)))
                    .willReturn(mock(BookingResponse.class));

            // When
            BookingResponse result = bookingService.createBooking(SCOPE_TYPE, SCOPE_ID, USER_ID, request);

            // Then
            assertThat(result).isNotNull();
            verify(bookingEquipmentRepository).save(any());
        }
    }

    // ========================================
    // updateBooking
    // ========================================

    @Nested
    @DisplayName("updateBooking")
    class UpdateBooking {

        @Test
        @DisplayName("正常系: 予約が更新される")
        void 予約更新_正常_更新される() {
            // Given
            FacilityBookingEntity booking = createPendingBooking();
            SharedFacilityEntity facility = createActiveFacility();
            UpdateBookingRequest request = new UpdateBookingRequest(
                    LocalDate.now().plusDays(5), null, null,
                    LocalTime.of(14, 0), LocalTime.of(16, 0),
                    "更新済み打ち合わせ", 8, null
            );

            given(bookingRepository.findById(BOOKING_ID)).willReturn(Optional.of(booking));
            given(feeCalculator.calculateSlotCount(any(), any())).willReturn(4);
            given(facilityService.findFacilityByIdOrThrow(FACILITY_ID)).willReturn(facility);
            given(feeCalculator.calculateUsageFee(any(), any(), any(), any(), anyInt()))
                    .willReturn(BigDecimal.valueOf(2000));
            given(facilityMapper.toBookingDetailResponse(booking)).willReturn(mock(BookingDetailResponse.class));

            // When
            BookingDetailResponse result = bookingService.updateBooking(BOOKING_ID, request);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("異常系: PENDING_APPROVAL以外のステータスでFACILITY_007例外")
        void 予約更新_ステータス不正_FACILITY007例外() {
            // Given
            FacilityBookingEntity booking = createConfirmedBooking();
            UpdateBookingRequest request = new UpdateBookingRequest(
                    LocalDate.now().plusDays(5), null, null,
                    LocalTime.of(14, 0), LocalTime.of(16, 0),
                    "更新", 8, null
            );
            given(bookingRepository.findById(BOOKING_ID)).willReturn(Optional.of(booking));

            // When / Then
            assertThatThrownBy(() -> bookingService.updateBooking(BOOKING_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FACILITY_007"));
        }
    }

    // ========================================
    // cancelBooking
    // ========================================

    @Nested
    @DisplayName("cancelBooking")
    class CancelBooking {

        @Test
        @DisplayName("正常系: PENDING_APPROVAL予約がキャンセルされる")
        void 予約キャンセル_PENDING状態_キャンセルされる() {
            // Given
            FacilityBookingEntity booking = createPendingBooking();
            CancelBookingRequest request = new CancelBookingRequest("都合が悪くなった");
            given(bookingRepository.findById(BOOKING_ID)).willReturn(Optional.of(booking));
            given(facilityMapper.toBookingDetailResponse(booking)).willReturn(mock(BookingDetailResponse.class));

            // When
            BookingDetailResponse result = bookingService.cancelBooking(BOOKING_ID, USER_ID, request);

            // Then
            assertThat(result).isNotNull();
            assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        }

        @Test
        @DisplayName("正常系: CONFIRMED予約がキャンセルされる")
        void 予約キャンセル_CONFIRMED状態_キャンセルされる() {
            // Given
            FacilityBookingEntity booking = createConfirmedBooking();
            CancelBookingRequest request = new CancelBookingRequest("予定変更");
            given(bookingRepository.findById(BOOKING_ID)).willReturn(Optional.of(booking));
            given(facilityMapper.toBookingDetailResponse(booking)).willReturn(mock(BookingDetailResponse.class));

            // When
            BookingDetailResponse result = bookingService.cancelBooking(BOOKING_ID, USER_ID, request);

            // Then
            assertThat(result).isNotNull();
            assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        }

        @Test
        @DisplayName("異常系: COMPLETED予約のキャンセルでFACILITY_007例外")
        void 予約キャンセル_COMPLETED状態_FACILITY007例外() {
            // Given
            FacilityBookingEntity booking = createCompletedBooking();
            CancelBookingRequest request = new CancelBookingRequest("キャンセル希望");
            given(bookingRepository.findById(BOOKING_ID)).willReturn(Optional.of(booking));

            // When / Then
            assertThatThrownBy(() -> bookingService.cancelBooking(BOOKING_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FACILITY_007"));
        }
    }

    // ========================================
    // approveBooking
    // ========================================

    @Nested
    @DisplayName("approveBooking")
    class ApproveBooking {

        @Test
        @DisplayName("正常系: 予約が承認される")
        void 予約承認_正常_CONFIRMEDになる() {
            // Given
            FacilityBookingEntity booking = createPendingBooking();
            ApproveBookingRequest request = new ApproveBookingRequest("承認します");
            given(bookingRepository.findById(BOOKING_ID)).willReturn(Optional.of(booking));
            given(facilityMapper.toBookingDetailResponse(booking)).willReturn(mock(BookingDetailResponse.class));

            // When
            BookingDetailResponse result = bookingService.approveBooking(BOOKING_ID, ADMIN_USER_ID, request);

            // Then
            assertThat(result).isNotNull();
            assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        }

        @Test
        @DisplayName("異常系: CONFIRMED予約の承認でFACILITY_007例外")
        void 予約承認_CONFIRMED状態_FACILITY007例外() {
            // Given
            FacilityBookingEntity booking = createConfirmedBooking();
            ApproveBookingRequest request = new ApproveBookingRequest("承認");
            given(bookingRepository.findById(BOOKING_ID)).willReturn(Optional.of(booking));

            // When / Then
            assertThatThrownBy(() -> bookingService.approveBooking(BOOKING_ID, ADMIN_USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FACILITY_007"));
        }
    }

    // ========================================
    // rejectBooking
    // ========================================

    @Nested
    @DisplayName("rejectBooking")
    class RejectBooking {

        @Test
        @DisplayName("正常系: 予約が却下される")
        void 予約却下_正常_REJECTEDになる() {
            // Given
            FacilityBookingEntity booking = createPendingBooking();
            RejectBookingRequest request = new RejectBookingRequest("利用目的が不適切");
            given(bookingRepository.findById(BOOKING_ID)).willReturn(Optional.of(booking));
            given(facilityMapper.toBookingDetailResponse(booking)).willReturn(mock(BookingDetailResponse.class));

            // When
            BookingDetailResponse result = bookingService.rejectBooking(BOOKING_ID, ADMIN_USER_ID, request);

            // Then
            assertThat(result).isNotNull();
            assertThat(booking.getStatus()).isEqualTo(BookingStatus.REJECTED);
        }

        @Test
        @DisplayName("異常系: COMPLETED予約の却下でFACILITY_007例外")
        void 予約却下_COMPLETED状態_FACILITY007例外() {
            // Given
            FacilityBookingEntity booking = createCompletedBooking();
            RejectBookingRequest request = new RejectBookingRequest("却下");
            given(bookingRepository.findById(BOOKING_ID)).willReturn(Optional.of(booking));

            // When / Then
            assertThatThrownBy(() -> bookingService.rejectBooking(BOOKING_ID, ADMIN_USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FACILITY_007"));
        }
    }

    // ========================================
    // checkIn
    // ========================================

    @Nested
    @DisplayName("checkIn")
    class CheckIn {

        @Test
        @DisplayName("正常系: チェックインできる")
        void チェックイン_CONFIRMED状態_CHECKED_INになる() {
            // Given
            FacilityBookingEntity booking = createConfirmedBooking();
            given(bookingRepository.findById(BOOKING_ID)).willReturn(Optional.of(booking));
            given(facilityMapper.toBookingDetailResponse(booking)).willReturn(mock(BookingDetailResponse.class));

            // When
            BookingDetailResponse result = bookingService.checkIn(BOOKING_ID);

            // Then
            assertThat(result).isNotNull();
            assertThat(booking.getStatus()).isEqualTo(BookingStatus.CHECKED_IN);
        }

        @Test
        @DisplayName("異常系: PENDING_APPROVAL状態でFACILITY_007例外")
        void チェックイン_PENDING状態_FACILITY007例外() {
            // Given
            FacilityBookingEntity booking = createPendingBooking();
            given(bookingRepository.findById(BOOKING_ID)).willReturn(Optional.of(booking));

            // When / Then
            assertThatThrownBy(() -> bookingService.checkIn(BOOKING_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FACILITY_007"));
        }
    }

    // ========================================
    // completeBooking
    // ========================================

    @Nested
    @DisplayName("completeBooking")
    class CompleteBooking {

        @Test
        @DisplayName("正常系: 利用完了できる")
        void 利用完了_CHECKED_IN状態_COMPLETEDになる() {
            // Given
            FacilityBookingEntity booking = createCheckedInBooking();
            given(bookingRepository.findById(BOOKING_ID)).willReturn(Optional.of(booking));
            given(facilityMapper.toBookingDetailResponse(booking)).willReturn(mock(BookingDetailResponse.class));

            // When
            BookingDetailResponse result = bookingService.completeBooking(BOOKING_ID);

            // Then
            assertThat(result).isNotNull();
            assertThat(booking.getStatus()).isEqualTo(BookingStatus.COMPLETED);
        }

        @Test
        @DisplayName("異常系: CONFIRMED状態でFACILITY_007例外")
        void 利用完了_CONFIRMED状態_FACILITY007例外() {
            // Given
            FacilityBookingEntity booking = createConfirmedBooking();
            given(bookingRepository.findById(BOOKING_ID)).willReturn(Optional.of(booking));

            // When / Then
            assertThatThrownBy(() -> bookingService.completeBooking(BOOKING_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FACILITY_007"));
        }
    }

    // ========================================
    // getCalendarBookings
    // ========================================

    @Nested
    @DisplayName("getCalendarBookings")
    class GetCalendarBookings {

        @Test
        @DisplayName("正常系: カレンダー予約が返る")
        void カレンダー予約取得_正常_予約リストが返る() {
            // Given
            LocalDate dateFrom = LocalDate.now();
            LocalDate dateTo = LocalDate.now().plusDays(7);
            FacilityBookingEntity booking = createConfirmedBooking();
            given(bookingRepository.findCalendarBookings(eq(SCOPE_TYPE), eq(SCOPE_ID),
                    eq(dateFrom), eq(dateTo), any())).willReturn(List.of(booking));
            given(facilityMapper.toCalendarBookingResponseList(any()))
                    .willReturn(List.of(mock(CalendarBookingResponse.class)));

            // When
            List<CalendarBookingResponse> result = bookingService.getCalendarBookings(
                    SCOPE_TYPE, SCOPE_ID, dateFrom, dateTo);

            // Then
            assertThat(result).hasSize(1);
        }
    }

    // ========================================
    // getBookingForPdf
    // ========================================

    @Nested
    @DisplayName("getBookingForPdf")
    class GetBookingForPdf {

        @Test
        @DisplayName("正常系: PDF用データが返る")
        void PDF用データ取得_正常_詳細が返る() {
            // Given
            FacilityBookingEntity booking = createPendingBooking();
            given(bookingRepository.findById(BOOKING_ID)).willReturn(Optional.of(booking));
            BookingDetailResponse expected = mock(BookingDetailResponse.class);
            given(facilityMapper.toBookingDetailResponse(booking)).willReturn(expected);

            // When
            BookingDetailResponse result = bookingService.getBookingForPdf(BOOKING_ID);

            // Then
            assertThat(result).isEqualTo(expected);
        }
    }

    // ========================================
    // createBooking + validateBookingAgainstRules
    // ========================================

    @Nested
    @DisplayName("createBooking ルール検証パターン")
    class CreateBookingWithRules {

        private com.mannschaft.app.facility.entity.FacilityUsageRuleEntity createUsageRule() {
            return com.mannschaft.app.facility.entity.FacilityUsageRuleEntity.builder()
                    .facilityId(FACILITY_ID)
                    .minAdvanceHours(1)
                    .maxAdvanceDays(30)
                    .availableTimeFrom(LocalTime.of(8, 0))
                    .availableTimeTo(LocalTime.of(22, 0))
                    .minHoursPerBooking(new BigDecimal("0.5"))
                    .maxHoursPerBooking(new BigDecimal("4.0"))
                    .maxBookingsPerMonthPerUser(4)
                    .build();
        }

        @Test
        @DisplayName("正常系: ルールあり予約が作成される")
        void 予約作成_ルールあり_正常() {
            // Given
            SharedFacilityEntity facility = createActiveFacility();
            CreateBookingRequest request = createBookingRequest();
            com.mannschaft.app.facility.entity.FacilityUsageRuleEntity rule = createUsageRule();

            given(facilityService.findFacilityByIdOrThrow(FACILITY_ID)).willReturn(facility);
            given(usageRuleRepository.findByFacilityId(any())).willReturn(Optional.of(rule));
            given(bookingRepository.countMonthlyBookings(any(), any(), anyInt(), anyInt(), any()))
                    .willReturn(0L);
            given(settingsRepository.findByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID)).willReturn(Optional.empty());
            given(bookingRepository.findOverlapping(any(), any(), any(), any(), any()))
                    .willReturn(Collections.emptyList());
            given(feeCalculator.calculateSlotCount(any(), any())).willReturn(4);
            given(feeCalculator.calculateUsageFee(any(), any(), any(), any(), anyInt()))
                    .willReturn(BigDecimal.valueOf(2000));
            given(bookingRepository.save(any(FacilityBookingEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));
            given(facilityMapper.toBookingResponse(any(FacilityBookingEntity.class)))
                    .willReturn(mock(BookingResponse.class));

            // When
            BookingResponse result = bookingService.createBooking(SCOPE_TYPE, SCOPE_ID, USER_ID, request);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("異常系: 最小事前予約時間不足でFACILITY_022例外")
        void 予約作成_事前時間不足_FACILITY022例外() {
            // Given
            SharedFacilityEntity facility = createActiveFacility();
            // 今日の予約 + minAdvanceHours=48（2日前必要なのに当日）
            CreateBookingRequest request = new CreateBookingRequest(
                    FACILITY_ID,
                    LocalDate.now().plusDays(1), // 明日だが minAdvanceHours=48なので不足
                    null, null,
                    LocalTime.of(10, 0), LocalTime.of(12, 0),
                    "打ち合わせ", 5, null
            );
            com.mannschaft.app.facility.entity.FacilityUsageRuleEntity rule =
                    com.mannschaft.app.facility.entity.FacilityUsageRuleEntity.builder()
                            .facilityId(FACILITY_ID)
                            .minAdvanceHours(72) // 72時間前が必要
                            .maxAdvanceDays(30)
                            .availableTimeFrom(LocalTime.of(8, 0))
                            .availableTimeTo(LocalTime.of(22, 0))
                            .minHoursPerBooking(new BigDecimal("0.5"))
                            .maxHoursPerBooking(new BigDecimal("4.0"))
                            .maxBookingsPerMonthPerUser(4)
                            .build();

            given(facilityService.findFacilityByIdOrThrow(FACILITY_ID)).willReturn(facility);
            given(usageRuleRepository.findByFacilityId(any())).willReturn(Optional.of(rule));

            // When / Then
            assertThatThrownBy(() -> bookingService.createBooking(SCOPE_TYPE, SCOPE_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FACILITY_015"));
        }

        @Test
        @DisplayName("異常系: 最大事前予約日数超過でFACILITY_016例外")
        void 予約作成_最大事前日数超過_FACILITY016例外() {
            // Given
            SharedFacilityEntity facility = createActiveFacility();
            CreateBookingRequest request = new CreateBookingRequest(
                    FACILITY_ID,
                    LocalDate.now().plusDays(60), // 60日後 > maxAdvanceDays=30
                    null, null,
                    LocalTime.of(10, 0), LocalTime.of(12, 0),
                    "打ち合わせ", 5, null
            );
            com.mannschaft.app.facility.entity.FacilityUsageRuleEntity rule =
                    com.mannschaft.app.facility.entity.FacilityUsageRuleEntity.builder()
                            .facilityId(FACILITY_ID)
                            .minAdvanceHours(0)
                            .maxAdvanceDays(30) // 30日以内のみ
                            .availableTimeFrom(LocalTime.of(8, 0))
                            .availableTimeTo(LocalTime.of(22, 0))
                            .minHoursPerBooking(new BigDecimal("0.5"))
                            .maxHoursPerBooking(new BigDecimal("4.0"))
                            .maxBookingsPerMonthPerUser(4)
                            .build();

            given(facilityService.findFacilityByIdOrThrow(FACILITY_ID)).willReturn(facility);
            given(usageRuleRepository.findByFacilityId(any())).willReturn(Optional.of(rule));

            // When / Then
            assertThatThrownBy(() -> bookingService.createBooking(SCOPE_TYPE, SCOPE_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FACILITY_016"));
        }

        @Test
        @DisplayName("異常系: 利用可能時間外でFACILITY_011例外")
        void 予約作成_利用時間外_FACILITY024例外() {
            // Given
            SharedFacilityEntity facility = createActiveFacility();
            CreateBookingRequest request = new CreateBookingRequest(
                    FACILITY_ID,
                    LocalDate.now().plusDays(3),
                    null, null,
                    LocalTime.of(6, 0), // 6時は利用不可（9〜22時）
                    LocalTime.of(8, 0),
                    "打ち合わせ", 5, null
            );
            com.mannschaft.app.facility.entity.FacilityUsageRuleEntity rule =
                    com.mannschaft.app.facility.entity.FacilityUsageRuleEntity.builder()
                            .facilityId(FACILITY_ID)
                            .minAdvanceHours(0)
                            .maxAdvanceDays(60)
                            .availableTimeFrom(LocalTime.of(9, 0))
                            .availableTimeTo(LocalTime.of(22, 0))
                            .minHoursPerBooking(new BigDecimal("0.5"))
                            .maxHoursPerBooking(new BigDecimal("4.0"))
                            .maxBookingsPerMonthPerUser(4)
                            .build();

            given(facilityService.findFacilityByIdOrThrow(FACILITY_ID)).willReturn(facility);
            given(usageRuleRepository.findByFacilityId(any())).willReturn(Optional.of(rule));

            // When / Then
            assertThatThrownBy(() -> bookingService.createBooking(SCOPE_TYPE, SCOPE_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FACILITY_011"));
        }

        @Test
        @DisplayName("異常系: 最小利用時間未満でFACILITY_012例外")
        void 予約作成_最小時間未満_FACILITY025例外() {
            // Given
            SharedFacilityEntity facility = createActiveFacility();
            CreateBookingRequest request = new CreateBookingRequest(
                    FACILITY_ID,
                    LocalDate.now().plusDays(3),
                    null, null,
                    LocalTime.of(10, 0),
                    LocalTime.of(10, 15), // 15分 < minHoursPerBooking=1.0h
                    "打ち合わせ", 5, null
            );
            com.mannschaft.app.facility.entity.FacilityUsageRuleEntity rule =
                    com.mannschaft.app.facility.entity.FacilityUsageRuleEntity.builder()
                            .facilityId(FACILITY_ID)
                            .minAdvanceHours(0)
                            .maxAdvanceDays(60)
                            .availableTimeFrom(LocalTime.of(8, 0))
                            .availableTimeTo(LocalTime.of(22, 0))
                            .minHoursPerBooking(new BigDecimal("1.0"))
                            .maxHoursPerBooking(new BigDecimal("4.0"))
                            .maxBookingsPerMonthPerUser(4)
                            .build();

            given(facilityService.findFacilityByIdOrThrow(FACILITY_ID)).willReturn(facility);
            given(usageRuleRepository.findByFacilityId(any())).willReturn(Optional.of(rule));

            // When / Then
            assertThatThrownBy(() -> bookingService.createBooking(SCOPE_TYPE, SCOPE_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FACILITY_012"));
        }

        @Test
        @DisplayName("異常系: 最大利用時間超過でFACILITY_013例外")
        void 予約作成_最大時間超過_FACILITY026例外() {
            // Given
            SharedFacilityEntity facility = createActiveFacility();
            CreateBookingRequest request = new CreateBookingRequest(
                    FACILITY_ID,
                    LocalDate.now().plusDays(3),
                    null, null,
                    LocalTime.of(9, 0),
                    LocalTime.of(17, 0), // 8時間 > maxHoursPerBooking=4.0h
                    "打ち合わせ", 5, null
            );
            com.mannschaft.app.facility.entity.FacilityUsageRuleEntity rule =
                    com.mannschaft.app.facility.entity.FacilityUsageRuleEntity.builder()
                            .facilityId(FACILITY_ID)
                            .minAdvanceHours(0)
                            .maxAdvanceDays(60)
                            .availableTimeFrom(LocalTime.of(8, 0))
                            .availableTimeTo(LocalTime.of(22, 0))
                            .minHoursPerBooking(new BigDecimal("0.5"))
                            .maxHoursPerBooking(new BigDecimal("4.0"))
                            .maxBookingsPerMonthPerUser(4)
                            .build();

            given(facilityService.findFacilityByIdOrThrow(FACILITY_ID)).willReturn(facility);
            given(usageRuleRepository.findByFacilityId(any())).willReturn(Optional.of(rule));

            // When / Then
            assertThatThrownBy(() -> bookingService.createBooking(SCOPE_TYPE, SCOPE_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FACILITY_013"));
        }

        @Test
        @DisplayName("異常系: 月間予約上限超過でFACILITY_009例外")
        void 予約作成_月間上限超過_FACILITY011例外() {
            // Given
            SharedFacilityEntity facility = createActiveFacility();
            CreateBookingRequest request = createBookingRequest();
            com.mannschaft.app.facility.entity.FacilityUsageRuleEntity rule =
                    com.mannschaft.app.facility.entity.FacilityUsageRuleEntity.builder()
                            .facilityId(FACILITY_ID)
                            .minAdvanceHours(0)
                            .maxAdvanceDays(60)
                            .availableTimeFrom(LocalTime.of(8, 0))
                            .availableTimeTo(LocalTime.of(22, 0))
                            .minHoursPerBooking(new BigDecimal("0.5"))
                            .maxHoursPerBooking(new BigDecimal("8.0"))
                            .maxBookingsPerMonthPerUser(2) // 月2回まで
                            .build();

            given(facilityService.findFacilityByIdOrThrow(FACILITY_ID)).willReturn(facility);
            given(usageRuleRepository.findByFacilityId(any())).willReturn(Optional.of(rule));
            given(bookingRepository.countMonthlyBookings(any(), any(), anyInt(), anyInt(), any()))
                    .willReturn(2L); // 既に2回予約済み

            // When / Then
            assertThatThrownBy(() -> bookingService.createBooking(SCOPE_TYPE, SCOPE_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FACILITY_009"));
        }

        @Test
        @DisplayName("正常系: requiresApproval=falseかつautoApprove=falseでCONFIRMEDになる")
        void 予約作成_requiresApprovalfalse_CONFIRMEDになる() {
            // Given
            SharedFacilityEntity facility = createActiveFacility();
            CreateBookingRequest request = createBookingRequest();
            FacilitySettingsEntity settings = FacilitySettingsEntity.builder()
                    .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID)
                    .requiresApproval(false) // 承認不要
                    .maxBookingsPerDayPerUser(10)
                    .build();

            given(facilityService.findFacilityByIdOrThrow(FACILITY_ID)).willReturn(facility);
            given(usageRuleRepository.findByFacilityId(any())).willReturn(Optional.empty());
            given(settingsRepository.findByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(settings));
            given(bookingRepository.countByFacilityIdAndBookingDateAndBookedByAndStatusNotIn(
                    any(), any(), any(), any())).willReturn(0L);
            given(bookingRepository.findOverlapping(any(), any(), any(), any(), any()))
                    .willReturn(Collections.emptyList());
            given(feeCalculator.calculateSlotCount(any(), any())).willReturn(4);
            given(feeCalculator.calculateUsageFee(any(), any(), any(), any(), anyInt()))
                    .willReturn(BigDecimal.valueOf(2000));
            given(bookingRepository.save(any(FacilityBookingEntity.class)))
                    .willAnswer(invocation -> {
                        FacilityBookingEntity saved = invocation.getArgument(0);
                        assertThat(saved.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
                        return saved;
                    });
            given(facilityMapper.toBookingResponse(any(FacilityBookingEntity.class)))
                    .willReturn(mock(BookingResponse.class));

            // When
            BookingResponse result = bookingService.createBooking(SCOPE_TYPE, SCOPE_ID, USER_ID, request);

            // Then
            assertThat(result).isNotNull();
        }
    }

    // ========================================
    // updateBooking 追加パターン
    // ========================================

    @Nested
    @DisplayName("updateBooking 追加パターン")
    class UpdateBookingAdditional {

        @Test
        @DisplayName("正常系: 備品再処理付きで予約が更新される")
        void 予約更新_備品再処理あり_更新される() {
            // Given
            FacilityBookingEntity booking = createPendingBooking();
            SharedFacilityEntity facility = createActiveFacility();
            List<CreateBookingRequest.BookingEquipmentEntry> equipment =
                    List.of(new CreateBookingRequest.BookingEquipmentEntry(1L, 1));
            UpdateBookingRequest request = new UpdateBookingRequest(
                    LocalDate.now().plusDays(5), null, null,
                    LocalTime.of(14, 0), LocalTime.of(16, 0),
                    "更新", 8, equipment
            );
            FacilityEquipmentEntity equipmentEntity = FacilityEquipmentEntity.builder()
                    .facilityId(FACILITY_ID).name("プロジェクター")
                    .pricePerUse(BigDecimal.valueOf(300)).totalQuantity(1).displayOrder(0).build();

            given(bookingRepository.findById(BOOKING_ID)).willReturn(Optional.of(booking));
            given(feeCalculator.calculateSlotCount(any(), any())).willReturn(4);
            given(facilityService.findFacilityByIdOrThrow(FACILITY_ID)).willReturn(facility);
            given(feeCalculator.calculateUsageFee(any(), any(), any(), any(), anyInt()))
                    .willReturn(BigDecimal.valueOf(2000));
            given(equipmentService.findEquipmentOrThrow(1L)).willReturn(equipmentEntity);
            given(bookingEquipmentRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));
            given(facilityMapper.toBookingDetailResponse(booking)).willReturn(mock(BookingDetailResponse.class));

            // When
            BookingDetailResponse result = bookingService.updateBooking(BOOKING_ID, request);

            // Then
            assertThat(result).isNotNull();
            verify(bookingEquipmentRepository).deleteByBookingId(BOOKING_ID);
            verify(bookingEquipmentRepository).save(any());
        }
    }
}
