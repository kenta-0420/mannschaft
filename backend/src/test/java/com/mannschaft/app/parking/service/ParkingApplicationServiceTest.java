package com.mannschaft.app.parking.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.parking.ApplicationStatus;
import com.mannschaft.app.parking.ParkingApplicationStatus;
import com.mannschaft.app.parking.ParkingMapper;
import com.mannschaft.app.parking.dto.ApplicationResponse;
import com.mannschaft.app.parking.dto.CreateApplicationRequest;
import com.mannschaft.app.parking.dto.RejectApplicationRequest;
import com.mannschaft.app.parking.entity.ParkingApplicationEntity;
import com.mannschaft.app.parking.entity.ParkingSpaceEntity;
import com.mannschaft.app.parking.repository.ParkingApplicationRepository;
import com.mannschaft.app.parking.repository.ParkingSpaceRepository;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputRecordRepository;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link ParkingApplicationService} の単体テスト。
 * 区画申請の作成・承認・拒否・キャンセル・抽選ロジックを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ParkingApplicationService 単体テスト")
class ParkingApplicationServiceTest {

    @Mock
    private ParkingApplicationRepository applicationRepository;

    @Mock
    private ParkingSpaceRepository spaceRepository;

    @Mock
    private ParkingMapper parkingMapper;

    @Mock
    private ProxyInputContext proxyInputContext;

    @Mock
    private ProxyInputRecordRepository proxyInputRecordRepository;

    @InjectMocks
    private ParkingApplicationService parkingApplicationService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long SPACE_ID = 1L;
    private static final Long USER_ID = 100L;
    private static final Long VEHICLE_ID = 200L;
    private static final Long APPLICATION_ID = 10L;

    private ParkingApplicationEntity createPendingApplication() {
        return ParkingApplicationEntity.builder()
                .spaceId(SPACE_ID)
                .userId(USER_ID)
                .vehicleId(VEHICLE_ID)
                .message("申請メッセージ")
                .build();
    }

    private ParkingApplicationEntity createLotteryPendingApplication() {
        ParkingApplicationEntity entity = ParkingApplicationEntity.builder()
                .spaceId(SPACE_ID)
                .userId(USER_ID)
                .vehicleId(VEHICLE_ID)
                .build();
        entity.markLotteryPending(1);
        return entity;
    }

    private ParkingSpaceEntity createAcceptingSpace() {
        return ParkingSpaceEntity.builder()
                .scopeType("TEAM")
                .scopeId(1L)
                .spaceNumber("A-001")
                .spaceType(com.mannschaft.app.parking.SpaceType.INDOOR)
                .applicationStatus(ApplicationStatus.ACCEPTING)
                .createdBy(1L)
                .build();
    }

    private ParkingSpaceEntity createNotAcceptingSpace() {
        return ParkingSpaceEntity.builder()
                .scopeType("TEAM")
                .scopeId(1L)
                .spaceNumber("A-002")
                .spaceType(com.mannschaft.app.parking.SpaceType.INDOOR)
                .applicationStatus(ApplicationStatus.NOT_ACCEPTING)
                .createdBy(1L)
                .build();
    }

    private ApplicationResponse createApplicationResponse() {
        return new ApplicationResponse(APPLICATION_ID, SPACE_ID, USER_ID, VEHICLE_ID,
                "VACANCY", null, "PENDING", 0, "申請メッセージ", null, null, null, null);
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
            Page<ApplicationResponse> result = parkingApplicationService.list(List.of(), null, pageable);

            // Then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("正常系: ステータス指定ありで絞り込み取得できる")
        void list_ステータス指定あり_絞り込み取得() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            List<Long> spaceIds = List.of(SPACE_ID);
            ParkingApplicationEntity entity = createPendingApplication();
            Page<ParkingApplicationEntity> page = new PageImpl<>(List.of(entity));
            given(applicationRepository.findBySpaceIdInAndStatus(spaceIds, ParkingApplicationStatus.PENDING, pageable))
                    .willReturn(page);
            given(parkingMapper.toApplicationResponse(entity)).willReturn(createApplicationResponse());

            // When
            Page<ApplicationResponse> result = parkingApplicationService.list(spaceIds, "PENDING", pageable);

            // Then
            assertThat(result.getContent()).hasSize(1);
        }

        @Test
        @DisplayName("正常系: ステータス指定なしで全件取得できる")
        void list_ステータス指定なし_全件取得() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            List<Long> spaceIds = List.of(SPACE_ID);
            Page<ParkingApplicationEntity> page = new PageImpl<>(List.of(createPendingApplication()));
            given(applicationRepository.findBySpaceIdIn(spaceIds, pageable)).willReturn(page);
            given(parkingMapper.toApplicationResponse(any())).willReturn(createApplicationResponse());

            // When
            Page<ApplicationResponse> result = parkingApplicationService.list(spaceIds, null, pageable);

            // Then
            assertThat(result.getContent()).hasSize(1);
        }
    }

    // ========================================
    // create
    // ========================================

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("正常系: 申請が作成される")
        void create_正常_申請が作成される() {
            // Given
            List<Long> spaceIds = List.of(SPACE_ID);
            CreateApplicationRequest request = new CreateApplicationRequest(SPACE_ID, VEHICLE_ID, "申請メッセージ");
            ParkingSpaceEntity space = createAcceptingSpace();
            given(spaceRepository.findById(SPACE_ID)).willReturn(Optional.of(space));
            given(applicationRepository.findBySpaceIdAndUserIdAndStatusIn(eq(SPACE_ID), eq(USER_ID), anyList()))
                    .willReturn(Optional.empty());
            given(applicationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(parkingMapper.toApplicationResponse(any())).willReturn(createApplicationResponse());

            // When
            ApplicationResponse result = parkingApplicationService.create(spaceIds, USER_ID, request);

            // Then
            assertThat(result).isNotNull();
            verify(applicationRepository).save(any(ParkingApplicationEntity.class));
        }

        @Test
        @DisplayName("異常系: スコープ不一致でPARKING_020例外")
        void create_スコープ不一致_PARKING020例外() {
            // Given
            List<Long> spaceIds = List.of(999L);
            CreateApplicationRequest request = new CreateApplicationRequest(SPACE_ID, VEHICLE_ID, null);

            // When / Then
            assertThatThrownBy(() -> parkingApplicationService.create(spaceIds, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_020"));
        }

        @Test
        @DisplayName("異常系: 区画が見つからない場合PARKING_001例外")
        void create_区画不在_PARKING001例外() {
            // Given
            List<Long> spaceIds = List.of(SPACE_ID);
            CreateApplicationRequest request = new CreateApplicationRequest(SPACE_ID, VEHICLE_ID, null);
            given(spaceRepository.findById(SPACE_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> parkingApplicationService.create(spaceIds, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_001"));
        }

        @Test
        @DisplayName("異常系: 申請受付中でない場合PARKING_013例外")
        void create_申請受付中でない_PARKING013例外() {
            // Given
            List<Long> spaceIds = List.of(SPACE_ID);
            CreateApplicationRequest request = new CreateApplicationRequest(SPACE_ID, VEHICLE_ID, null);
            given(spaceRepository.findById(SPACE_ID)).willReturn(Optional.of(createNotAcceptingSpace()));

            // When / Then
            assertThatThrownBy(() -> parkingApplicationService.create(spaceIds, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_013"));
        }

        @Test
        @DisplayName("異常系: 重複申請でPARKING_014例外")
        void create_重複申請_PARKING014例外() {
            // Given
            List<Long> spaceIds = List.of(SPACE_ID);
            CreateApplicationRequest request = new CreateApplicationRequest(SPACE_ID, VEHICLE_ID, null);
            given(spaceRepository.findById(SPACE_ID)).willReturn(Optional.of(createAcceptingSpace()));
            given(applicationRepository.findBySpaceIdAndUserIdAndStatusIn(eq(SPACE_ID), eq(USER_ID), anyList()))
                    .willReturn(Optional.of(createPendingApplication()));

            // When / Then
            assertThatThrownBy(() -> parkingApplicationService.create(spaceIds, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_014"));
        }
    }

    // ========================================
    // approve
    // ========================================

    @Nested
    @DisplayName("approve")
    class Approve {

        @Test
        @DisplayName("正常系: PENDINGの申請が承認される")
        void approve_PENDING_承認される() {
            // Given
            ParkingApplicationEntity entity = createPendingApplication();
            given(applicationRepository.findById(APPLICATION_ID)).willReturn(Optional.of(entity));
            given(applicationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(parkingMapper.toApplicationResponse(any())).willReturn(createApplicationResponse());

            // When
            ApplicationResponse result = parkingApplicationService.approve(APPLICATION_ID);

            // Then
            assertThat(result).isNotNull();
            assertThat(entity.getStatus()).isEqualTo(ParkingApplicationStatus.APPROVED);
            verify(applicationRepository).save(entity);
        }

        @Test
        @DisplayName("正常系: LOTTERY_PENDINGの申請が承認される")
        void approve_LOTTERY_PENDING_承認される() {
            // Given
            ParkingApplicationEntity entity = createLotteryPendingApplication();
            given(applicationRepository.findById(APPLICATION_ID)).willReturn(Optional.of(entity));
            given(applicationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(parkingMapper.toApplicationResponse(any())).willReturn(createApplicationResponse());

            // When
            ApplicationResponse result = parkingApplicationService.approve(APPLICATION_ID);

            // Then
            assertThat(result).isNotNull();
            assertThat(entity.getStatus()).isEqualTo(ParkingApplicationStatus.APPROVED);
        }

        @Test
        @DisplayName("異常系: 申請が見つからない場合PARKING_004例外")
        void approve_申請不在_PARKING004例外() {
            // Given
            given(applicationRepository.findById(APPLICATION_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> parkingApplicationService.approve(APPLICATION_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_004"));
        }

        @Test
        @DisplayName("異常系: APPROVED状態でPARKING_015例外")
        void approve_APPROVED状態_PARKING015例外() {
            // Given
            ParkingApplicationEntity entity = createPendingApplication();
            entity.approve(); // 既にAPPROVED
            given(applicationRepository.findById(APPLICATION_ID)).willReturn(Optional.of(entity));

            // When / Then
            assertThatThrownBy(() -> parkingApplicationService.approve(APPLICATION_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_015"));
        }
    }

    // ========================================
    // reject
    // ========================================

    @Nested
    @DisplayName("reject")
    class Reject {

        @Test
        @DisplayName("正常系: 申請が拒否される")
        void reject_正常_拒否される() {
            // Given
            ParkingApplicationEntity entity = createPendingApplication();
            RejectApplicationRequest request = new RejectApplicationRequest("条件不備");
            given(applicationRepository.findById(APPLICATION_ID)).willReturn(Optional.of(entity));
            given(applicationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(parkingMapper.toApplicationResponse(any())).willReturn(createApplicationResponse());

            // When
            ApplicationResponse result = parkingApplicationService.reject(APPLICATION_ID, request);

            // Then
            assertThat(result).isNotNull();
            assertThat(entity.getStatus()).isEqualTo(ParkingApplicationStatus.REJECTED);
            assertThat(entity.getRejectionReason()).isEqualTo("条件不備");
        }

        @Test
        @DisplayName("異常系: 申請が見つからない場合PARKING_004例外")
        void reject_申請不在_PARKING004例外() {
            // Given
            given(applicationRepository.findById(APPLICATION_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> parkingApplicationService.reject(APPLICATION_ID, new RejectApplicationRequest("理由")))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_004"));
        }

        @Test
        @DisplayName("異常系: CANCELLED状態でPARKING_015例外")
        void reject_CANCELLED状態_PARKING015例外() {
            // Given
            ParkingApplicationEntity entity = createPendingApplication();
            entity.cancel();
            given(applicationRepository.findById(APPLICATION_ID)).willReturn(Optional.of(entity));

            // When / Then
            assertThatThrownBy(() -> parkingApplicationService.reject(APPLICATION_ID, new RejectApplicationRequest("理由")))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_015"));
        }
    }

    // ========================================
    // cancel
    // ========================================

    @Nested
    @DisplayName("cancel")
    class Cancel {

        @Test
        @DisplayName("正常系: 申請がキャンセルされる")
        void cancel_正常_キャンセルされる() {
            // Given
            ParkingApplicationEntity entity = createPendingApplication();
            given(applicationRepository.findById(APPLICATION_ID)).willReturn(Optional.of(entity));

            // When
            parkingApplicationService.cancel(APPLICATION_ID, USER_ID);

            // Then
            assertThat(entity.getStatus()).isEqualTo(ParkingApplicationStatus.CANCELLED);
            verify(applicationRepository).save(entity);
        }

        @Test
        @DisplayName("異常系: 申請が見つからない場合PARKING_004例外")
        void cancel_申請不在_PARKING004例外() {
            // Given
            given(applicationRepository.findById(APPLICATION_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> parkingApplicationService.cancel(APPLICATION_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_004"));
        }

        @Test
        @DisplayName("異常系: REJECTED状態でPARKING_015例外")
        void cancel_REJECTED状態_PARKING015例外() {
            // Given
            ParkingApplicationEntity entity = createPendingApplication();
            entity.reject("拒否理由");
            given(applicationRepository.findById(APPLICATION_ID)).willReturn(Optional.of(entity));

            // When / Then
            assertThatThrownBy(() -> parkingApplicationService.cancel(APPLICATION_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_015"));
        }
    }

    // ========================================
    // executeLottery
    // ========================================

    @Nested
    @DisplayName("executeLottery")
    class ExecuteLottery {

        @Test
        @DisplayName("正常系: 抽選が実行される")
        void executeLottery_正常_抽選実行() {
            // Given
            ParkingSpaceEntity space = createAcceptingSpace();
            given(spaceRepository.findById(SPACE_ID)).willReturn(Optional.of(space));

            ParkingApplicationEntity app1 = createPendingApplication();
            ParkingApplicationEntity app2 = ParkingApplicationEntity.builder()
                    .spaceId(SPACE_ID).userId(101L).vehicleId(201L).build();
            given(applicationRepository.findBySpaceIdAndStatus(SPACE_ID, ParkingApplicationStatus.PENDING))
                    .willReturn(new ArrayList<>(List.of(app1, app2)));
            given(parkingMapper.toApplicationResponseList(anyList())).willReturn(List.of(createApplicationResponse()));

            // When
            java.util.List<ApplicationResponse> result = parkingApplicationService.executeLottery(SPACE_ID);

            // Then
            assertThat(result).isNotNull();
            assertThat(app1.getStatus()).isEqualTo(ParkingApplicationStatus.LOTTERY_PENDING);
            assertThat(app2.getStatus()).isEqualTo(ParkingApplicationStatus.LOTTERY_PENDING);
            assertThat(space.getApplicationStatus()).isEqualTo(ApplicationStatus.LOTTERY_CLOSED);
            verify(applicationRepository).saveAll(anyList());
            verify(spaceRepository).save(space);
        }

        @Test
        @DisplayName("異常系: 区画が見つからない場合PARKING_001例外")
        void executeLottery_区画不在_PARKING001例外() {
            // Given
            given(spaceRepository.findById(SPACE_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> parkingApplicationService.executeLottery(SPACE_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_001"));
        }

        @Test
        @DisplayName("異常系: 候補者がいない場合PARKING_033例外")
        void executeLottery_候補者なし_PARKING033例外() {
            // Given
            given(spaceRepository.findById(SPACE_ID)).willReturn(Optional.of(createAcceptingSpace()));
            given(applicationRepository.findBySpaceIdAndStatus(SPACE_ID, ParkingApplicationStatus.PENDING))
                    .willReturn(List.of());

            // When / Then
            assertThatThrownBy(() -> parkingApplicationService.executeLottery(SPACE_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_033"));
        }

        @Test
        @DisplayName("境界値: 候補者が1人の場合でも抽選実行できる")
        void executeLottery_候補者1人_抽選実行() {
            // Given
            ParkingSpaceEntity space = createAcceptingSpace();
            given(spaceRepository.findById(SPACE_ID)).willReturn(Optional.of(space));
            ParkingApplicationEntity singleApp = createPendingApplication();
            given(applicationRepository.findBySpaceIdAndStatus(SPACE_ID, ParkingApplicationStatus.PENDING))
                    .willReturn(List.of(singleApp));
            given(parkingMapper.toApplicationResponseList(anyList())).willReturn(List.of(createApplicationResponse()));

            // When
            java.util.List<ApplicationResponse> result = parkingApplicationService.executeLottery(SPACE_ID);

            // Then
            assertThat(result).isNotNull();
            assertThat(singleApp.getLotteryNumber()).isEqualTo(1);
        }
    }
}
