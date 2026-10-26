package com.mannschaft.app.facility.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.facility.BookingStatus;
import com.mannschaft.app.facility.FacilityMapper;
import com.mannschaft.app.facility.FacilityType;
import com.mannschaft.app.facility.dto.AvailabilityResponse;
import com.mannschaft.app.facility.dto.CreateFacilityRequest;
import com.mannschaft.app.facility.dto.FacilityDetailResponse;
import com.mannschaft.app.facility.dto.FacilityResponse;
import com.mannschaft.app.facility.dto.UpdateFacilityRequest;
import com.mannschaft.app.facility.entity.FacilityBookingEntity;
import com.mannschaft.app.facility.entity.FacilityUsageRuleEntity;
import com.mannschaft.app.facility.entity.SharedFacilityEntity;
import com.mannschaft.app.facility.repository.FacilityBookingRepository;
import com.mannschaft.app.facility.repository.FacilityUsageRuleRepository;
import com.mannschaft.app.facility.repository.SharedFacilityRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * {@link FacilityService} の単体テスト。
 * 施設CRUD・空き状況取得を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FacilityService 単体テスト")
class FacilityServiceTest {

    @Mock
    private SharedFacilityRepository facilityRepository;

    @Mock
    private FacilityUsageRuleRepository usageRuleRepository;

    @Mock
    private FacilityBookingRepository bookingRepository;

    @Mock
    private FacilityMapper facilityMapper;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private FacilityService facilityService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final String SCOPE_TYPE = "TEAM";
    private static final Long SCOPE_ID = 1L;
    private static final Long USER_ID = 100L;
    private static final Long FACILITY_ID = 5L;

    private SharedFacilityEntity createFacilityEntity() {
        return SharedFacilityEntity.builder()
                .scopeType(SCOPE_TYPE)
                .scopeId(SCOPE_ID)
                .name("会議室A")
                .facilityType(FacilityType.MEETING_ROOM)
                .capacity(10)
                .isActive(true)
                .autoApprove(false)
                .cleaningBufferMinutes(0)
                .ratePerSlot(BigDecimal.valueOf(500))
                .displayOrder(0)
                .createdBy(USER_ID)
                .build();
    }

    private CreateFacilityRequest createFacilityRequest() {
        return new CreateFacilityRequest(
                "会議室A", "MEETING_ROOM", null, 10,
                "3F", "3階フロア", "会議室の説明",
                null, BigDecimal.valueOf(500), null,
                null, null, 0, false, 0
        );
    }

    // ========================================
    // listFacilities
    // ========================================

    @Nested
    @DisplayName("listFacilities")
    class ListFacilities {

        @Test
        @DisplayName("正常系: 施設一覧が返る")
        void 施設一覧取得_正常_リストが返る() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            SharedFacilityEntity entity = createFacilityEntity();
            Page<SharedFacilityEntity> page = new PageImpl<>(List.of(entity));
            given(facilityRepository.findByScopeTypeAndScopeIdOrderByDisplayOrderAsc(SCOPE_TYPE, SCOPE_ID, pageable))
                    .willReturn(page);
            given(facilityMapper.toFacilityResponse(entity)).willReturn(mock(FacilityResponse.class));

            // When
            Page<FacilityResponse> result = facilityService.listFacilities(SCOPE_TYPE, SCOPE_ID, pageable);

            // Then
            assertThat(result.getTotalElements()).isEqualTo(1);
        }
    }

    // ========================================
    // getFacility
    // ========================================

    @Nested
    @DisplayName("getFacility")
    class GetFacility {

        @Test
        @DisplayName("正常系: 施設詳細が返る")
        void 施設詳細取得_正常_詳細が返る() {
            // Given
            SharedFacilityEntity entity = createFacilityEntity();
            given(facilityRepository.findByIdAndScopeTypeAndScopeId(FACILITY_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(facilityMapper.toFacilityDetailResponse(entity)).willReturn(mock(FacilityDetailResponse.class));

            // When
            FacilityDetailResponse result = facilityService.getFacility(SCOPE_TYPE, SCOPE_ID, FACILITY_ID);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("異常系: 施設が存在しないでFACILITY_001例外")
        void 施設詳細取得_存在しない_FACILITY001例外() {
            // Given
            given(facilityRepository.findByIdAndScopeTypeAndScopeId(FACILITY_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> facilityService.getFacility(SCOPE_TYPE, SCOPE_ID, FACILITY_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FACILITY_001"));
        }
    }

    // ========================================
    // createFacility
    // ========================================

    @Nested
    @DisplayName("createFacility")
    class CreateFacility {

        @Test
        @DisplayName("正常系: 施設が作成される")
        void 施設作成_正常_作成される() {
            // Given
            CreateFacilityRequest request = createFacilityRequest();
            given(facilityRepository.existsByScopeTypeAndScopeIdAndNameAndDeletedAtIsNull(
                    SCOPE_TYPE, SCOPE_ID, "会議室A")).willReturn(false);
            given(facilityRepository.save(any(SharedFacilityEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));
            given(usageRuleRepository.save(any(FacilityUsageRuleEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));
            given(facilityMapper.toFacilityResponse(any(SharedFacilityEntity.class)))
                    .willReturn(mock(FacilityResponse.class));

            // When
            FacilityResponse result = facilityService.createFacility(SCOPE_TYPE, SCOPE_ID, USER_ID, request);

            // Then
            assertThat(result).isNotNull();
            verify(facilityRepository).save(any(SharedFacilityEntity.class));
            verify(usageRuleRepository).save(any(FacilityUsageRuleEntity.class));
        }

        @Test
        @DisplayName("異常系: 施設名重複でFACILITY_002例外")
        void 施設作成_名前重複_FACILITY002例外() {
            // Given
            CreateFacilityRequest request = createFacilityRequest();
            given(facilityRepository.existsByScopeTypeAndScopeIdAndNameAndDeletedAtIsNull(
                    SCOPE_TYPE, SCOPE_ID, "会議室A")).willReturn(true);

            // When / Then
            assertThatThrownBy(() -> facilityService.createFacility(SCOPE_TYPE, SCOPE_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FACILITY_002"));
        }

        @Test
        @DisplayName("正常系: デフォルト値が適用される（cleaningBuffer=null, autoApprove=null）")
        void 施設作成_デフォルト値_デフォルトが適用される() {
            // Given
            CreateFacilityRequest request = new CreateFacilityRequest(
                    "BBQエリア", "BBQ_AREA", null, 30,
                    null, null, null, null, null, null,
                    null, null, null, null, null
            );
            given(facilityRepository.existsByScopeTypeAndScopeIdAndNameAndDeletedAtIsNull(
                    SCOPE_TYPE, SCOPE_ID, "BBQエリア")).willReturn(false);
            given(facilityRepository.save(any(SharedFacilityEntity.class)))
                    .willAnswer(invocation -> {
                        SharedFacilityEntity saved = invocation.getArgument(0);
                        assertThat(saved.getCleaningBufferMinutes()).isEqualTo(0);
                        assertThat(saved.getAutoApprove()).isFalse();
                        assertThat(saved.getDisplayOrder()).isEqualTo(0);
                        return saved;
                    });
            given(usageRuleRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));
            given(facilityMapper.toFacilityResponse(any(SharedFacilityEntity.class)))
                    .willReturn(mock(FacilityResponse.class));

            // When
            FacilityResponse result = facilityService.createFacility(SCOPE_TYPE, SCOPE_ID, USER_ID, request);

            // Then
            assertThat(result).isNotNull();
        }
    }

    // ========================================
    // bulkCreateFacilities
    // ========================================

    @Nested
    @DisplayName("bulkCreateFacilities")
    class BulkCreateFacilities {

        @Test
        @DisplayName("正常系: 複数施設が一括作成される")
        void 施設一括作成_正常_全件作成される() {
            // Given
            CreateFacilityRequest request1 = new CreateFacilityRequest(
                    "会議室A", "MEETING_ROOM", null, 10,
                    null, null, null, null, null, null,
                    null, null, null, null, null
            );
            CreateFacilityRequest request2 = new CreateFacilityRequest(
                    "会議室B", "MEETING_ROOM", null, 20,
                    null, null, null, null, null, null,
                    null, null, null, null, null
            );

            given(facilityRepository.existsByScopeTypeAndScopeIdAndNameAndDeletedAtIsNull(
                    eq(SCOPE_TYPE), eq(SCOPE_ID), any())).willReturn(false);
            given(facilityRepository.save(any(SharedFacilityEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));
            given(usageRuleRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));
            given(facilityMapper.toFacilityResponse(any(SharedFacilityEntity.class)))
                    .willReturn(mock(FacilityResponse.class));

            // When
            List<FacilityResponse> result = facilityService.bulkCreateFacilities(
                    SCOPE_TYPE, SCOPE_ID, USER_ID, List.of(request1, request2));

            // Then
            assertThat(result).hasSize(2);
        }
    }

    // ========================================
    // updateFacility
    // ========================================

    @Nested
    @DisplayName("updateFacility")
    class UpdateFacility {

        @Test
        @DisplayName("正常系: 施設が更新される")
        void 施設更新_正常_更新される() {
            // Given
            SharedFacilityEntity entity = createFacilityEntity();
            UpdateFacilityRequest request = new UpdateFacilityRequest(
                    "会議室A改", "MEETING_ROOM", "大会議室", 20,
                    "5F", "5階フロア", "リニューアル済み",
                    null, BigDecimal.valueOf(800), null,
                    null, null, 10, true, true, 1
            );
            given(facilityRepository.findByIdAndScopeTypeAndScopeId(FACILITY_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(facilityMapper.toFacilityDetailResponse(entity)).willReturn(mock(FacilityDetailResponse.class));

            // When
            FacilityDetailResponse result = facilityService.updateFacility(
                    SCOPE_TYPE, SCOPE_ID, FACILITY_ID, request);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("異常系: 施設が存在しないでFACILITY_001例外")
        void 施設更新_存在しない_FACILITY001例外() {
            // Given
            UpdateFacilityRequest request = new UpdateFacilityRequest(
                    "更新名", "MEETING_ROOM", null, 10,
                    null, null, null, null, null, null,
                    null, null, null, null, null, null
            );
            given(facilityRepository.findByIdAndScopeTypeAndScopeId(FACILITY_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> facilityService.updateFacility(SCOPE_TYPE, SCOPE_ID, FACILITY_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FACILITY_001"));
        }
    }

    // ========================================
    // deleteFacility
    // ========================================

    @Nested
    @DisplayName("deleteFacility")
    class DeleteFacility {

        @Test
        @DisplayName("正常系: 施設が論理削除される")
        void 施設削除_正常_論理削除される() {
            // Given
            SharedFacilityEntity entity = createFacilityEntity();
            given(facilityRepository.findByIdAndScopeTypeAndScopeId(FACILITY_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));

            // When
            facilityService.deleteFacility(SCOPE_TYPE, SCOPE_ID, FACILITY_ID);

            // Then
            assertThat(entity.getDeletedAt()).isNotNull();
        }

        @Test
        @DisplayName("異常系: 施設が存在しないでFACILITY_001例外")
        void 施設削除_存在しない_FACILITY001例外() {
            // Given
            given(facilityRepository.findByIdAndScopeTypeAndScopeId(FACILITY_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> facilityService.deleteFacility(SCOPE_TYPE, SCOPE_ID, FACILITY_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FACILITY_001"));
        }
    }

    // ========================================
    // getAvailability
    // ========================================

    @Nested
    @DisplayName("getAvailability")
    class GetAvailability {

        @Test
        @DisplayName("正常系: 空きスロットが返る")
        void 空き状況取得_正常_スロットが返る() {
            // Given
            SharedFacilityEntity facility = createFacilityEntity();
            FacilityUsageRuleEntity rule = FacilityUsageRuleEntity.builder()
                    .facilityId(FACILITY_ID)
                    .availableTimeFrom(LocalTime.of(9, 0))
                    .availableTimeTo(LocalTime.of(10, 0))
                    .build();
            LocalDate date = LocalDate.now().plusDays(1);

            given(facilityRepository.findByIdAndScopeTypeAndScopeId(FACILITY_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(facility));
            given(usageRuleRepository.findByFacilityId(FACILITY_ID)).willReturn(Optional.of(rule));
            given(bookingRepository.findByFacilityIdAndBookingDateAndStatusNotIn(eq(FACILITY_ID), eq(date), any()))
                    .willReturn(Collections.emptyList());

            // When
            AvailabilityResponse result = facilityService.getAvailability(
                    SCOPE_TYPE, SCOPE_ID, FACILITY_ID, date);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getSlots()).hasSize(2); // 9:00-9:30, 9:30-10:00
            assertThat(result.getSlots()).allSatisfy(slot ->
                    assertThat(slot.getAvailable()).isTrue());
        }

        @Test
        @DisplayName("正常系: 予約済みスロットは利用不可として返る")
        void 空き状況取得_予約済み_利用不可スロットが含まれる() {
            // Given
            SharedFacilityEntity facility = createFacilityEntity();
            FacilityUsageRuleEntity rule = FacilityUsageRuleEntity.builder()
                    .facilityId(FACILITY_ID)
                    .availableTimeFrom(LocalTime.of(9, 0))
                    .availableTimeTo(LocalTime.of(10, 0))
                    .build();
            LocalDate date = LocalDate.now().plusDays(1);

            FacilityBookingEntity existingBooking = FacilityBookingEntity.builder()
                    .facilityId(FACILITY_ID)
                    .bookedBy(1L)
                    .bookingDate(date)
                    .timeFrom(LocalTime.of(9, 0))
                    .timeTo(LocalTime.of(9, 30))
                    .slotCount(1)
                    .status(BookingStatus.CONFIRMED)
                    .build();

            given(facilityRepository.findByIdAndScopeTypeAndScopeId(FACILITY_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(facility));
            given(usageRuleRepository.findByFacilityId(FACILITY_ID)).willReturn(Optional.of(rule));
            given(bookingRepository.findByFacilityIdAndBookingDateAndStatusNotIn(eq(FACILITY_ID), eq(date), any()))
                    .willReturn(List.of(existingBooking));

            // When
            AvailabilityResponse result = facilityService.getAvailability(
                    SCOPE_TYPE, SCOPE_ID, FACILITY_ID, date);

            // Then
            assertThat(result.getSlots()).hasSize(2);
            // 9:00-9:30のスロットは利用不可
            assertThat(result.getSlots().get(0).getAvailable()).isFalse();
            // 9:30-10:00のスロットは利用可
            assertThat(result.getSlots().get(1).getAvailable()).isTrue();
        }

        @Test
        @DisplayName("異常系: ルールが存在しないでFACILITY_005例外")
        void 空き状況取得_ルールなし_FACILITY005例外() {
            // Given
            SharedFacilityEntity facility = createFacilityEntity();
            LocalDate date = LocalDate.now().plusDays(1);
            given(facilityRepository.findByIdAndScopeTypeAndScopeId(FACILITY_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(facility));
            given(usageRuleRepository.findByFacilityId(FACILITY_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> facilityService.getAvailability(SCOPE_TYPE, SCOPE_ID, FACILITY_ID, date))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FACILITY_005"));
        }
    }

    // ========================================
    // findFacilityOrThrow
    // ========================================

    @Nested
    @DisplayName("findFacilityOrThrow")
    class FindFacilityOrThrow {

        @Test
        @DisplayName("正常系: 施設エンティティが返る")
        void 施設取得_正常_エンティティが返る() {
            // Given
            SharedFacilityEntity entity = createFacilityEntity();
            given(facilityRepository.findByIdAndScopeTypeAndScopeId(FACILITY_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));

            // When
            SharedFacilityEntity result = facilityService.findFacilityOrThrow(SCOPE_TYPE, SCOPE_ID, FACILITY_ID);

            // Then
            assertThat(result).isEqualTo(entity);
        }

        @Test
        @DisplayName("異常系: 施設が存在しないでFACILITY_001例外")
        void 施設取得_存在しない_FACILITY001例外() {
            // Given
            given(facilityRepository.findByIdAndScopeTypeAndScopeId(FACILITY_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> facilityService.findFacilityOrThrow(SCOPE_TYPE, SCOPE_ID, FACILITY_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FACILITY_001"));
        }
    }

    // ========================================
    // findFacilityByIdOrThrow
    // ========================================

    @Nested
    @DisplayName("findFacilityByIdOrThrow")
    class FindFacilityByIdOrThrow {

        @Test
        @DisplayName("正常系: IDで施設エンティティが返る")
        void 施設ID取得_正常_エンティティが返る() {
            // Given
            SharedFacilityEntity entity = createFacilityEntity();
            given(facilityRepository.findById(FACILITY_ID)).willReturn(Optional.of(entity));

            // When
            SharedFacilityEntity result = facilityService.findFacilityByIdOrThrow(FACILITY_ID);

            // Then
            assertThat(result).isEqualTo(entity);
        }

        @Test
        @DisplayName("異常系: IDで施設が存在しないでFACILITY_001例外")
        void 施設ID取得_存在しない_FACILITY001例外() {
            // Given
            given(facilityRepository.findById(FACILITY_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> facilityService.findFacilityByIdOrThrow(FACILITY_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FACILITY_001"));
        }
    }
}
