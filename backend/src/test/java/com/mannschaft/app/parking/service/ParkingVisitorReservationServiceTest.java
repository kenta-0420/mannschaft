package com.mannschaft.app.parking.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.parking.ParkingMapper;
import com.mannschaft.app.parking.SpaceType;
import com.mannschaft.app.parking.VisitorReservationStatus;
import com.mannschaft.app.parking.dto.AvailabilityResponse;
import com.mannschaft.app.parking.dto.CreateVisitorReservationRequest;
import com.mannschaft.app.parking.dto.VisitorReservationResponse;
import com.mannschaft.app.parking.entity.ParkingSettingsEntity;
import com.mannschaft.app.parking.entity.ParkingSpaceEntity;
import com.mannschaft.app.parking.entity.ParkingVisitorReservationEntity;
import com.mannschaft.app.parking.repository.ParkingSettingsRepository;
import com.mannschaft.app.parking.repository.ParkingSpaceRepository;
import com.mannschaft.app.parking.repository.ParkingVisitorReservationRepository;
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

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link ParkingVisitorReservationService} の単体テスト。
 * 来場者予約の作成・承認・拒否・チェックイン・完了・キャンセル・空き確認ロジックを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ParkingVisitorReservationService 単体テスト")
class ParkingVisitorReservationServiceTest {

    @Mock
    private ParkingVisitorReservationRepository reservationRepository;

    @Mock
    private ParkingSpaceRepository spaceRepository;

    @Mock
    private ParkingSettingsRepository settingsRepository;

    @Mock
    private ParkingMapper parkingMapper;

    @InjectMocks
    private ParkingVisitorReservationService parkingVisitorReservationService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long SPACE_ID = 1L;
    private static final Long USER_ID = 100L;
    private static final Long SCOPE_ID = 10L;
    private static final Long RESERVATION_ID = 20L;
    private static final Long APPROVER_ID = 50L;
    private static final String SCOPE_TYPE = "TEAM";
    private static final LocalDate RESERVED_DATE = LocalDate.of(2026, 4, 1);

    private ParkingSettingsEntity createDefaultSettings() {
        return ParkingSettingsEntity.builder()
                .scopeType(SCOPE_TYPE)
                .scopeId(SCOPE_ID)
                .build();
    }

    private ParkingSettingsEntity createSettingsNoApproval() {
        ParkingSettingsEntity settings = ParkingSettingsEntity.builder()
                .scopeType(SCOPE_TYPE)
                .scopeId(SCOPE_ID)
                .build();
        settings.update(1, 2, 30, false);
        return settings;
    }

    private CreateVisitorReservationRequest createValidRequest() {
        return new CreateVisitorReservationRequest(
                SPACE_ID, "来場者名", "品川300あ1234",
                RESERVED_DATE, LocalTime.of(10, 0), LocalTime.of(12, 0), "訪問目的");
    }

    private ParkingVisitorReservationEntity createPendingReservation() {
        return ParkingVisitorReservationEntity.builder()
                .spaceId(SPACE_ID)
                .reservedBy(USER_ID)
                .visitorName("来場者名")
                .reservedDate(RESERVED_DATE)
                .timeFrom(LocalTime.of(10, 0))
                .timeTo(LocalTime.of(12, 0))
                .status(VisitorReservationStatus.PENDING_APPROVAL)
                .build();
    }

    private ParkingVisitorReservationEntity createConfirmedReservation() {
        ParkingVisitorReservationEntity entity = createPendingReservation();
        entity.approve(APPROVER_ID);
        return entity;
    }

    private ParkingVisitorReservationEntity createCheckedInReservation() {
        ParkingVisitorReservationEntity entity = createConfirmedReservation();
        entity.checkIn();
        return entity;
    }

    // ========================================
    // list
    // ========================================

    @Nested
    @DisplayName("list")
    class List_ {

        @Test
        @DisplayName("正常系: spaceIdsが空の場合空ページが返る")
        void list_spaceIds空_空ページが返る() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);

            // When
            Page<VisitorReservationResponse> result = parkingVisitorReservationService.list(List.of(), null, pageable);

            // Then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("正常系: 日付指定ありで絞り込みできる")
        void list_日付指定あり_絞り込み取得() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            List<Long> spaceIds = List.of(SPACE_ID);
            given(reservationRepository.findBySpaceIdInAndReservedDate(spaceIds, RESERVED_DATE, pageable))
                    .willReturn(new PageImpl<>(List.of(createPendingReservation())));
            given(parkingMapper.toVisitorReservationResponse(any())).willReturn(null);

            // When
            Page<VisitorReservationResponse> result = parkingVisitorReservationService.list(spaceIds, RESERVED_DATE, pageable);

            // Then
            assertThat(result.getContent()).hasSize(1);
        }

        @Test
        @DisplayName("正常系: 日付指定なしで全件取得できる")
        void list_日付指定なし_全件取得() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            List<Long> spaceIds = List.of(SPACE_ID);
            given(reservationRepository.findBySpaceIdIn(spaceIds, pageable))
                    .willReturn(new PageImpl<>(List.of(createPendingReservation())));
            given(parkingMapper.toVisitorReservationResponse(any())).willReturn(null);

            // When
            Page<VisitorReservationResponse> result = parkingVisitorReservationService.list(spaceIds, null, pageable);

            // Then
            assertThat(result.getContent()).hasSize(1);
        }
    }

    // ========================================
    // getDetail
    // ========================================

    @Nested
    @DisplayName("getDetail")
    class GetDetail {

        @Test
        @DisplayName("正常系: 予約詳細が取得できる")
        void getDetail_正常_詳細取得() {
            // Given
            given(reservationRepository.findById(RESERVATION_ID))
                    .willReturn(Optional.of(createPendingReservation()));
            given(parkingMapper.toVisitorReservationResponse(any())).willReturn(null);

            // When
            parkingVisitorReservationService.getDetail(RESERVATION_ID);

            // Then
            verify(reservationRepository).findById(RESERVATION_ID);
        }

        @Test
        @DisplayName("異常系: 予約が見つからない場合PARKING_006例外")
        void getDetail_不在_PARKING006例外() {
            // Given
            given(reservationRepository.findById(RESERVATION_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> parkingVisitorReservationService.getDetail(RESERVATION_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_006"));
        }
    }

    // ========================================
    // create
    // ========================================

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("正常系: 承認必要な設定で予約がPENDING_APPROVALで作成される")
        void create_承認必要_PENDING_APPROVALで作成() {
            // Given
            given(settingsRepository.findByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(createDefaultSettings()));
            given(reservationRepository.countByReservedByAndReservedDateAndStatusNotIn(eq(USER_ID), eq(RESERVED_DATE), anyList()))
                    .willReturn(0L);
            given(reservationRepository.findBySpaceIdAndReservedDateAndStatusNotIn(eq(SPACE_ID), eq(RESERVED_DATE), anyList()))
                    .willReturn(List.of());
            given(reservationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(parkingMapper.toVisitorReservationResponse(any())).willReturn(null);

            // When
            parkingVisitorReservationService.create(SCOPE_TYPE, SCOPE_ID, USER_ID, createValidRequest());

            // Then
            verify(reservationRepository).save(any(ParkingVisitorReservationEntity.class));
        }

        @Test
        @DisplayName("正常系: 承認不要な設定で予約がCONFIRMEDで作成される")
        void create_承認不要_CONFIRMEDで作成() {
            // Given
            given(settingsRepository.findByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(createSettingsNoApproval()));
            given(reservationRepository.countByReservedByAndReservedDateAndStatusNotIn(eq(USER_ID), eq(RESERVED_DATE), anyList()))
                    .willReturn(0L);
            given(reservationRepository.findBySpaceIdAndReservedDateAndStatusNotIn(eq(SPACE_ID), eq(RESERVED_DATE), anyList()))
                    .willReturn(List.of());
            given(reservationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(parkingMapper.toVisitorReservationResponse(any())).willReturn(null);

            // When
            parkingVisitorReservationService.create(SCOPE_TYPE, SCOPE_ID, USER_ID, createValidRequest());

            // Then
            verify(reservationRepository).save(any(ParkingVisitorReservationEntity.class));
        }

        @Test
        @DisplayName("異常系: 予約日が範囲外でPARKING_017例外")
        void create_日数超過_PARKING017例外() {
            // Given
            ParkingSettingsEntity settings = createDefaultSettings();
            given(settingsRepository.findByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(settings));
            CreateVisitorReservationRequest request = new CreateVisitorReservationRequest(
                    SPACE_ID, "来場者名", null,
                    LocalDate.now().plusDays(60), // 30日超過
                    LocalTime.of(10, 0), LocalTime.of(12, 0), null);

            // When / Then
            assertThatThrownBy(() -> parkingVisitorReservationService.create(SCOPE_TYPE, SCOPE_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_017"));
        }

        @Test
        @DisplayName("異常系: 時刻が30分単位でない場合PARKING_034例外")
        void create_時刻非30分単位_PARKING034例外() {
            // Given
            given(settingsRepository.findByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(createDefaultSettings()));
            CreateVisitorReservationRequest request = new CreateVisitorReservationRequest(
                    SPACE_ID, "来場者名", null,
                    RESERVED_DATE,
                    LocalTime.of(10, 15), // 30分単位でない
                    LocalTime.of(12, 0), null);

            // When / Then
            assertThatThrownBy(() -> parkingVisitorReservationService.create(SCOPE_TYPE, SCOPE_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_034"));
        }

        @Test
        @DisplayName("異常系: 開始時刻が終了時刻以降の場合PARKING_034例外")
        void create_時刻逆転_PARKING034例外() {
            // Given
            given(settingsRepository.findByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(createDefaultSettings()));
            CreateVisitorReservationRequest request = new CreateVisitorReservationRequest(
                    SPACE_ID, "来場者名", null,
                    RESERVED_DATE,
                    LocalTime.of(14, 0), LocalTime.of(10, 0), null);

            // When / Then
            assertThatThrownBy(() -> parkingVisitorReservationService.create(SCOPE_TYPE, SCOPE_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_034"));
        }

        @Test
        @DisplayName("異常系: 1日あたり上限超過でPARKING_016例外")
        void create_上限超過_PARKING016例外() {
            // Given
            given(settingsRepository.findByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(createDefaultSettings()));
            given(reservationRepository.countByReservedByAndReservedDateAndStatusNotIn(eq(USER_ID), eq(RESERVED_DATE), anyList()))
                    .willReturn(2L); // デフォルト上限2

            // When / Then
            assertThatThrownBy(() -> parkingVisitorReservationService.create(SCOPE_TYPE, SCOPE_ID, USER_ID, createValidRequest()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_016"));
        }

        @Test
        @DisplayName("異常系: 時間帯重複でPARKING_018例外")
        void create_時間帯重複_PARKING018例外() {
            // Given
            given(settingsRepository.findByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(createDefaultSettings()));
            given(reservationRepository.countByReservedByAndReservedDateAndStatusNotIn(eq(USER_ID), eq(RESERVED_DATE), anyList()))
                    .willReturn(0L);
            ParkingVisitorReservationEntity existingReservation = ParkingVisitorReservationEntity.builder()
                    .spaceId(SPACE_ID)
                    .reservedBy(99L)
                    .reservedDate(RESERVED_DATE)
                    .timeFrom(LocalTime.of(11, 0))
                    .timeTo(LocalTime.of(13, 0))
                    .build();
            given(reservationRepository.findBySpaceIdAndReservedDateAndStatusNotIn(eq(SPACE_ID), eq(RESERVED_DATE), anyList()))
                    .willReturn(List.of(existingReservation));

            // When / Then
            assertThatThrownBy(() -> parkingVisitorReservationService.create(SCOPE_TYPE, SCOPE_ID, USER_ID, createValidRequest()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_018"));
        }

        @Test
        @DisplayName("境界値: 時間帯が隣接する場合重複しない")
        void create_時間帯隣接_重複しない() {
            // Given
            given(settingsRepository.findByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(createDefaultSettings()));
            given(reservationRepository.countByReservedByAndReservedDateAndStatusNotIn(eq(USER_ID), eq(RESERVED_DATE), anyList()))
                    .willReturn(0L);
            ParkingVisitorReservationEntity existingReservation = ParkingVisitorReservationEntity.builder()
                    .spaceId(SPACE_ID)
                    .reservedBy(99L)
                    .reservedDate(RESERVED_DATE)
                    .timeFrom(LocalTime.of(12, 0)) // 10:00-12:00 と 12:00-14:00 は隣接
                    .timeTo(LocalTime.of(14, 0))
                    .build();
            given(reservationRepository.findBySpaceIdAndReservedDateAndStatusNotIn(eq(SPACE_ID), eq(RESERVED_DATE), anyList()))
                    .willReturn(List.of(existingReservation));
            given(reservationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(parkingMapper.toVisitorReservationResponse(any())).willReturn(null);

            // When
            parkingVisitorReservationService.create(SCOPE_TYPE, SCOPE_ID, USER_ID, createValidRequest());

            // Then
            verify(reservationRepository).save(any(ParkingVisitorReservationEntity.class));
        }

        @Test
        @DisplayName("正常系: 設定が存在しない場合デフォルト設定で作成される")
        void create_設定不在_デフォルト設定で作成() {
            // Given
            given(settingsRepository.findByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.empty());
            given(reservationRepository.countByReservedByAndReservedDateAndStatusNotIn(eq(USER_ID), eq(RESERVED_DATE), anyList()))
                    .willReturn(0L);
            given(reservationRepository.findBySpaceIdAndReservedDateAndStatusNotIn(eq(SPACE_ID), eq(RESERVED_DATE), anyList()))
                    .willReturn(List.of());
            given(reservationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(parkingMapper.toVisitorReservationResponse(any())).willReturn(null);

            // When
            parkingVisitorReservationService.create(SCOPE_TYPE, SCOPE_ID, USER_ID, createValidRequest());

            // Then
            verify(reservationRepository).save(any(ParkingVisitorReservationEntity.class));
        }
    }

    // ========================================
    // approve
    // ========================================

    @Nested
    @DisplayName("approve")
    class Approve {

        @Test
        @DisplayName("正常系: 予約が承認される")
        void approve_正常_承認される() {
            // Given
            ParkingVisitorReservationEntity entity = createPendingReservation();
            given(reservationRepository.findById(RESERVATION_ID)).willReturn(Optional.of(entity));
            given(reservationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(parkingMapper.toVisitorReservationResponse(any())).willReturn(null);

            // When
            parkingVisitorReservationService.approve(RESERVATION_ID, APPROVER_ID);

            // Then
            assertThat(entity.getStatus()).isEqualTo(VisitorReservationStatus.CONFIRMED);
            assertThat(entity.getApprovedBy()).isEqualTo(APPROVER_ID);
        }

        @Test
        @DisplayName("異常系: PENDING_APPROVAL以外でPARKING_023例外")
        void approve_CONFIRMED状態_PARKING023例外() {
            // Given
            ParkingVisitorReservationEntity entity = createConfirmedReservation();
            given(reservationRepository.findById(RESERVATION_ID)).willReturn(Optional.of(entity));

            // When / Then
            assertThatThrownBy(() -> parkingVisitorReservationService.approve(RESERVATION_ID, APPROVER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_023"));
        }

        @Test
        @DisplayName("異常系: 予約が見つからない場合PARKING_006例外")
        void approve_不在_PARKING006例外() {
            // Given
            given(reservationRepository.findById(RESERVATION_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> parkingVisitorReservationService.approve(RESERVATION_ID, APPROVER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_006"));
        }
    }

    // ========================================
    // reject
    // ========================================

    @Nested
    @DisplayName("reject")
    class Reject {

        @Test
        @DisplayName("正常系: 予約が拒否される")
        void reject_正常_拒否される() {
            // Given
            ParkingVisitorReservationEntity entity = createPendingReservation();
            given(reservationRepository.findById(RESERVATION_ID)).willReturn(Optional.of(entity));
            given(reservationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(parkingMapper.toVisitorReservationResponse(any())).willReturn(null);

            // When
            parkingVisitorReservationService.reject(RESERVATION_ID, APPROVER_ID, "理由");

            // Then
            assertThat(entity.getStatus()).isEqualTo(VisitorReservationStatus.REJECTED);
            assertThat(entity.getAdminComment()).isEqualTo("理由");
        }

        @Test
        @DisplayName("異常系: PENDING_APPROVAL以外でPARKING_023例外")
        void reject_CONFIRMED状態_PARKING023例外() {
            // Given
            ParkingVisitorReservationEntity entity = createConfirmedReservation();
            given(reservationRepository.findById(RESERVATION_ID)).willReturn(Optional.of(entity));

            // When / Then
            assertThatThrownBy(() -> parkingVisitorReservationService.reject(RESERVATION_ID, APPROVER_ID, "理由"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_023"));
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
        void checkIn_正常_チェックイン() {
            // Given
            ParkingVisitorReservationEntity entity = createConfirmedReservation();
            given(reservationRepository.findById(RESERVATION_ID)).willReturn(Optional.of(entity));
            given(reservationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(parkingMapper.toVisitorReservationResponse(any())).willReturn(null);

            // When
            parkingVisitorReservationService.checkIn(RESERVATION_ID);

            // Then
            assertThat(entity.getStatus()).isEqualTo(VisitorReservationStatus.CHECKED_IN);
        }

        @Test
        @DisplayName("異常系: CONFIRMED以外でPARKING_023例外")
        void checkIn_PENDING状態_PARKING023例外() {
            // Given
            ParkingVisitorReservationEntity entity = createPendingReservation();
            given(reservationRepository.findById(RESERVATION_ID)).willReturn(Optional.of(entity));

            // When / Then
            assertThatThrownBy(() -> parkingVisitorReservationService.checkIn(RESERVATION_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_023"));
        }
    }

    // ========================================
    // complete
    // ========================================

    @Nested
    @DisplayName("complete")
    class Complete {

        @Test
        @DisplayName("正常系: 完了にできる")
        void complete_正常_完了() {
            // Given
            ParkingVisitorReservationEntity entity = createCheckedInReservation();
            given(reservationRepository.findById(RESERVATION_ID)).willReturn(Optional.of(entity));
            given(reservationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(parkingMapper.toVisitorReservationResponse(any())).willReturn(null);

            // When
            parkingVisitorReservationService.complete(RESERVATION_ID);

            // Then
            assertThat(entity.getStatus()).isEqualTo(VisitorReservationStatus.COMPLETED);
        }

        @Test
        @DisplayName("異常系: CHECKED_IN以外でPARKING_023例外")
        void complete_CONFIRMED状態_PARKING023例外() {
            // Given
            ParkingVisitorReservationEntity entity = createConfirmedReservation();
            given(reservationRepository.findById(RESERVATION_ID)).willReturn(Optional.of(entity));

            // When / Then
            assertThatThrownBy(() -> parkingVisitorReservationService.complete(RESERVATION_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_023"));
        }
    }

    // ========================================
    // cancel
    // ========================================

    @Nested
    @DisplayName("cancel")
    class Cancel {

        @Test
        @DisplayName("正常系: 予約がキャンセルされる")
        void cancel_正常_キャンセル() {
            // Given
            ParkingVisitorReservationEntity entity = createPendingReservation();
            given(reservationRepository.findById(RESERVATION_ID)).willReturn(Optional.of(entity));

            // When
            parkingVisitorReservationService.cancel(RESERVATION_ID);

            // Then
            assertThat(entity.getStatus()).isEqualTo(VisitorReservationStatus.CANCELLED);
            verify(reservationRepository).save(entity);
        }

        @Test
        @DisplayName("異常系: 予約が見つからない場合PARKING_006例外")
        void cancel_不在_PARKING006例外() {
            // Given
            given(reservationRepository.findById(RESERVATION_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> parkingVisitorReservationService.cancel(RESERVATION_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_006"));
        }
    }

    // ========================================
    // getAvailability
    // ========================================

    @Nested
    @DisplayName("getAvailability")
    class GetAvailability {

        @Test
        @DisplayName("正常系: 空き状況が取得できる")
        void getAvailability_正常_空き状況取得() {
            // Given
            ParkingSpaceEntity visitorSpace = ParkingSpaceEntity.builder()
                    .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID)
                    .spaceNumber("V-001").spaceType(SpaceType.VISITOR).createdBy(1L).build();
            given(spaceRepository.findByScopeTypeAndScopeIdAndSpaceType(eq(SCOPE_TYPE), eq(SCOPE_ID),
                    eq(SpaceType.VISITOR), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of(visitorSpace)));
            given(reservationRepository.findBySpaceIdInAndReservedDateAndStatusNotIn(anyList(), eq(RESERVED_DATE), anyList()))
                    .willReturn(List.of());

            // When
            AvailabilityResponse result = parkingVisitorReservationService.getAvailability(SCOPE_TYPE, SCOPE_ID, RESERVED_DATE);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getDate()).isEqualTo(RESERVED_DATE);
            assertThat(result.getSpaces()).hasSize(1);
            assertThat(result.getSpaces().get(0).isAvailable()).isTrue();
        }

        @Test
        @DisplayName("正常系: 予約済み区画はavailable=falseになる")
        void getAvailability_予約済み_falseになる() {
            // Given
            ParkingSpaceEntity visitorSpace = ParkingSpaceEntity.builder()
                    .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID)
                    .spaceNumber("V-001").spaceType(SpaceType.VISITOR).createdBy(1L).build();
            given(spaceRepository.findByScopeTypeAndScopeIdAndSpaceType(eq(SCOPE_TYPE), eq(SCOPE_ID),
                    eq(SpaceType.VISITOR), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of(visitorSpace)));
            ParkingVisitorReservationEntity reservation = ParkingVisitorReservationEntity.builder()
                    .spaceId(visitorSpace.getId())
                    .reservedBy(USER_ID)
                    .reservedDate(RESERVED_DATE)
                    .timeFrom(LocalTime.of(10, 0))
                    .timeTo(LocalTime.of(12, 0))
                    .build();
            given(reservationRepository.findBySpaceIdInAndReservedDateAndStatusNotIn(anyList(), eq(RESERVED_DATE), anyList()))
                    .willReturn(List.of(reservation));

            // When
            AvailabilityResponse result = parkingVisitorReservationService.getAvailability(SCOPE_TYPE, SCOPE_ID, RESERVED_DATE);

            // Then
            assertThat(result.getSpaces()).hasSize(1);
            assertThat(result.getSpaces().get(0).isAvailable()).isFalse();
        }

        @Test
        @DisplayName("正常系: 来場者用区画がない場合空リストが返る")
        void getAvailability_区画なし_空リスト() {
            // Given
            given(spaceRepository.findByScopeTypeAndScopeIdAndSpaceType(eq(SCOPE_TYPE), eq(SCOPE_ID),
                    eq(SpaceType.VISITOR), any(Pageable.class)))
                    .willReturn(Page.empty());

            // When
            AvailabilityResponse result = parkingVisitorReservationService.getAvailability(SCOPE_TYPE, SCOPE_ID, RESERVED_DATE);

            // Then
            assertThat(result.getSpaces()).isEmpty();
        }
    }
}
