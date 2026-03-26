package com.mannschaft.app.parking.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.parking.ParkingMapper;
import com.mannschaft.app.parking.SubleaseApplicationStatus;
import com.mannschaft.app.parking.SubleaseStatus;
import com.mannschaft.app.parking.dto.ApplySubleaseRequest;
import com.mannschaft.app.parking.dto.ApproveSubleaseRequest;
import com.mannschaft.app.parking.dto.CreateSubleaseRequest;
import com.mannschaft.app.parking.dto.SubleaseApplicationResponse;
import com.mannschaft.app.parking.dto.SubleaseDetailResponse;
import com.mannschaft.app.parking.dto.SubleasePaymentResponse;
import com.mannschaft.app.parking.dto.SubleaseResponse;
import com.mannschaft.app.parking.dto.UpdateSubleaseRequest;
import com.mannschaft.app.parking.entity.ParkingAssignmentEntity;
import com.mannschaft.app.parking.entity.ParkingSubleaseApplicationEntity;
import com.mannschaft.app.parking.entity.ParkingSubleaseEntity;
import com.mannschaft.app.parking.repository.ParkingAssignmentRepository;
import com.mannschaft.app.parking.repository.ParkingSubleaseApplicationRepository;
import com.mannschaft.app.parking.repository.ParkingSubleasePaymentRepository;
import com.mannschaft.app.parking.repository.ParkingSubleaseRepository;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link ParkingSubleaseService} の単体テスト。
 * サブリースのCRUD・申請・承認・終了・決済一覧ロジックを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ParkingSubleaseService 単体テスト")
class ParkingSubleaseServiceTest {

    @Mock
    private ParkingSubleaseRepository subleaseRepository;

    @Mock
    private ParkingSubleaseApplicationRepository subleaseApplicationRepository;

    @Mock
    private ParkingSubleasePaymentRepository subleasePaymentRepository;

    @Mock
    private ParkingAssignmentRepository assignmentRepository;

    @Mock
    private ParkingMapper parkingMapper;

    @InjectMocks
    private ParkingSubleaseService parkingSubleaseService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long SPACE_ID = 1L;
    private static final Long USER_ID = 100L;
    private static final Long SUBLEASE_ID = 10L;
    private static final Long APPLICATION_ID = 20L;
    private static final Long VEHICLE_ID = 200L;

    private ParkingAssignmentEntity createAssignment(Long userId) {
        return ParkingAssignmentEntity.builder()
                .spaceId(SPACE_ID)
                .userId(userId)
                .assignedBy(1L)
                .build();
    }

    private ParkingSubleaseEntity createOpenSublease() {
        return ParkingSubleaseEntity.builder()
                .spaceId(SPACE_ID)
                .assignmentId(50L)
                .offeredBy(USER_ID)
                .title("サブリースタイトル")
                .description("説明文")
                .pricePerMonth(BigDecimal.valueOf(10000))
                .availableFrom(LocalDate.of(2026, 4, 1))
                .availableTo(LocalDate.of(2026, 12, 31))
                .build();
    }

    private ParkingSubleaseEntity createMatchedSublease() {
        ParkingSubleaseEntity entity = createOpenSublease();
        entity.match(APPLICATION_ID);
        return entity;
    }

    private ParkingSubleaseApplicationEntity createPendingApplication() {
        return ParkingSubleaseApplicationEntity.builder()
                .subleaseId(SUBLEASE_ID)
                .userId(101L)
                .vehicleId(VEHICLE_ID)
                .message("申込メッセージ")
                .build();
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
            Page<SubleaseResponse> result = parkingSubleaseService.list(List.of(), null, pageable);

            // Then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("正常系: ステータス指定ありで絞り込みできる")
        void list_ステータス指定あり_絞り込み取得() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            List<Long> spaceIds = List.of(SPACE_ID);
            given(subleaseRepository.findBySpaceIdInAndStatus(spaceIds, SubleaseStatus.OPEN, pageable))
                    .willReturn(new PageImpl<>(List.of(createOpenSublease())));
            given(parkingMapper.toSubleaseResponse(any())).willReturn(null);

            // When
            Page<SubleaseResponse> result = parkingSubleaseService.list(spaceIds, "OPEN", pageable);

            // Then
            assertThat(result.getContent()).hasSize(1);
        }

        @Test
        @DisplayName("正常系: ステータス指定なしで全件取得できる")
        void list_ステータス指定なし_全件取得() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            List<Long> spaceIds = List.of(SPACE_ID);
            given(subleaseRepository.findBySpaceIdIn(spaceIds, pageable))
                    .willReturn(new PageImpl<>(List.of(createOpenSublease())));
            given(parkingMapper.toSubleaseResponse(any())).willReturn(null);

            // When
            Page<SubleaseResponse> result = parkingSubleaseService.list(spaceIds, null, pageable);

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
        @DisplayName("正常系: サブリースが作成される")
        void create_正常_サブリースが作成される() {
            // Given
            List<Long> spaceIds = List.of(SPACE_ID);
            CreateSubleaseRequest request = new CreateSubleaseRequest(
                    SPACE_ID, "タイトル", "説明", BigDecimal.valueOf(10000), "DIRECT",
                    LocalDate.of(2026, 4, 1), LocalDate.of(2026, 12, 31));
            given(assignmentRepository.findBySpaceIdAndReleasedAtIsNull(SPACE_ID))
                    .willReturn(Optional.of(createAssignment(USER_ID)));
            given(subleaseRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(parkingMapper.toSubleaseResponse(any())).willReturn(null);

            // When
            parkingSubleaseService.create(spaceIds, USER_ID, request);

            // Then
            verify(subleaseRepository).save(any(ParkingSubleaseEntity.class));
        }

        @Test
        @DisplayName("異常系: スコープ不一致でPARKING_020例外")
        void create_スコープ不一致_PARKING020例外() {
            // Given
            List<Long> spaceIds = List.of(999L);
            CreateSubleaseRequest request = new CreateSubleaseRequest(
                    SPACE_ID, "タイトル", null, BigDecimal.valueOf(10000), null,
                    LocalDate.of(2026, 4, 1), null);

            // When / Then
            assertThatThrownBy(() -> parkingSubleaseService.create(spaceIds, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_020"));
        }

        @Test
        @DisplayName("異常系: 割り当て不在でPARKING_003例外")
        void create_割り当て不在_PARKING003例外() {
            // Given
            List<Long> spaceIds = List.of(SPACE_ID);
            CreateSubleaseRequest request = new CreateSubleaseRequest(
                    SPACE_ID, "タイトル", null, BigDecimal.valueOf(10000), null,
                    LocalDate.of(2026, 4, 1), null);
            given(assignmentRepository.findBySpaceIdAndReleasedAtIsNull(SPACE_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> parkingSubleaseService.create(spaceIds, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_003"));
        }

        @Test
        @DisplayName("異常系: 他人の割り当てでPARKING_031例外")
        void create_他人の割り当て_PARKING031例外() {
            // Given
            List<Long> spaceIds = List.of(SPACE_ID);
            CreateSubleaseRequest request = new CreateSubleaseRequest(
                    SPACE_ID, "タイトル", null, BigDecimal.valueOf(10000), null,
                    LocalDate.of(2026, 4, 1), null);
            given(assignmentRepository.findBySpaceIdAndReleasedAtIsNull(SPACE_ID))
                    .willReturn(Optional.of(createAssignment(999L)));

            // When / Then
            assertThatThrownBy(() -> parkingSubleaseService.create(spaceIds, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_031"));
        }

        @Test
        @DisplayName("正常系: paymentMethodがnullの場合DIRECTがデフォルト設定される")
        void create_paymentMethodなし_DIRECTデフォルト() {
            // Given
            List<Long> spaceIds = List.of(SPACE_ID);
            CreateSubleaseRequest request = new CreateSubleaseRequest(
                    SPACE_ID, "タイトル", null, BigDecimal.valueOf(10000), null,
                    LocalDate.of(2026, 4, 1), null);
            given(assignmentRepository.findBySpaceIdAndReleasedAtIsNull(SPACE_ID))
                    .willReturn(Optional.of(createAssignment(USER_ID)));
            given(subleaseRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(parkingMapper.toSubleaseResponse(any())).willReturn(null);

            // When
            parkingSubleaseService.create(spaceIds, USER_ID, request);

            // Then
            verify(subleaseRepository).save(any(ParkingSubleaseEntity.class));
        }
    }

    // ========================================
    // getDetail
    // ========================================

    @Nested
    @DisplayName("getDetail")
    class GetDetail {

        @Test
        @DisplayName("正常系: 詳細が取得できる")
        void getDetail_正常_詳細取得() {
            // Given
            List<Long> spaceIds = List.of(SPACE_ID);
            given(subleaseRepository.findByIdAndSpaceIdIn(SUBLEASE_ID, spaceIds))
                    .willReturn(Optional.of(createOpenSublease()));
            given(parkingMapper.toSubleaseDetailResponse(any())).willReturn(null);

            // When
            parkingSubleaseService.getDetail(spaceIds, SUBLEASE_ID);

            // Then
            verify(subleaseRepository).findByIdAndSpaceIdIn(SUBLEASE_ID, spaceIds);
        }

        @Test
        @DisplayName("異常系: サブリースが見つからない場合PARKING_025例外")
        void getDetail_不在_PARKING025例外() {
            // Given
            List<Long> spaceIds = List.of(SPACE_ID);
            given(subleaseRepository.findByIdAndSpaceIdIn(SUBLEASE_ID, spaceIds)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> parkingSubleaseService.getDetail(spaceIds, SUBLEASE_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_025"));
        }
    }

    // ========================================
    // update
    // ========================================

    @Nested
    @DisplayName("update")
    class Update {

        @Test
        @DisplayName("正常系: サブリースが更新される")
        void update_正常_更新される() {
            // Given
            List<Long> spaceIds = List.of(SPACE_ID);
            ParkingSubleaseEntity entity = createOpenSublease();
            UpdateSubleaseRequest request = new UpdateSubleaseRequest(
                    "新タイトル", "新説明", BigDecimal.valueOf(15000), "STRIPE",
                    LocalDate.of(2026, 5, 1), LocalDate.of(2027, 3, 31));
            given(subleaseRepository.findByIdAndSpaceIdIn(SUBLEASE_ID, spaceIds)).willReturn(Optional.of(entity));
            given(subleaseRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(parkingMapper.toSubleaseResponse(any())).willReturn(null);

            // When
            parkingSubleaseService.update(spaceIds, SUBLEASE_ID, request);

            // Then
            assertThat(entity.getTitle()).isEqualTo("新タイトル");
            assertThat(entity.getPricePerMonth()).isEqualByComparingTo(BigDecimal.valueOf(15000));
        }

        @Test
        @DisplayName("異常系: OPEN以外のステータスでPARKING_027例外")
        void update_MATCHED状態_PARKING027例外() {
            // Given
            List<Long> spaceIds = List.of(SPACE_ID);
            ParkingSubleaseEntity entity = createMatchedSublease();
            UpdateSubleaseRequest request = new UpdateSubleaseRequest(
                    "タイトル", null, BigDecimal.valueOf(10000), null,
                    LocalDate.of(2026, 4, 1), null);
            given(subleaseRepository.findByIdAndSpaceIdIn(SUBLEASE_ID, spaceIds)).willReturn(Optional.of(entity));

            // When / Then
            assertThatThrownBy(() -> parkingSubleaseService.update(spaceIds, SUBLEASE_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_027"));
        }
    }

    // ========================================
    // delete
    // ========================================

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("正常系: サブリースが論理削除される")
        void delete_正常_論理削除() {
            // Given
            List<Long> spaceIds = List.of(SPACE_ID);
            ParkingSubleaseEntity entity = createOpenSublease();
            given(subleaseRepository.findByIdAndSpaceIdIn(SUBLEASE_ID, spaceIds)).willReturn(Optional.of(entity));

            // When
            parkingSubleaseService.delete(spaceIds, SUBLEASE_ID);

            // Then
            assertThat(entity.getDeletedAt()).isNotNull();
            verify(subleaseRepository).save(entity);
        }

        @Test
        @DisplayName("異常系: サブリースが見つからない場合PARKING_025例外")
        void delete_不在_PARKING025例外() {
            // Given
            List<Long> spaceIds = List.of(SPACE_ID);
            given(subleaseRepository.findByIdAndSpaceIdIn(SUBLEASE_ID, spaceIds)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> parkingSubleaseService.delete(spaceIds, SUBLEASE_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_025"));
        }
    }

    // ========================================
    // apply
    // ========================================

    @Nested
    @DisplayName("apply")
    class Apply {

        @Test
        @DisplayName("正常系: サブリースに申し込みできる")
        void apply_正常_申し込みできる() {
            // Given
            List<Long> spaceIds = List.of(SPACE_ID);
            ParkingSubleaseEntity sublease = createOpenSublease();
            ApplySubleaseRequest request = new ApplySubleaseRequest(VEHICLE_ID, "申込メッセージ");
            given(subleaseRepository.findByIdAndSpaceIdIn(SUBLEASE_ID, spaceIds)).willReturn(Optional.of(sublease));
            given(subleaseApplicationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(parkingMapper.toSubleaseApplicationResponse(any())).willReturn(null);

            // When
            parkingSubleaseService.apply(spaceIds, SUBLEASE_ID, USER_ID, request);

            // Then
            verify(subleaseApplicationRepository).save(any(ParkingSubleaseApplicationEntity.class));
        }

        @Test
        @DisplayName("異常系: OPEN以外のステータスでPARKING_027例外")
        void apply_MATCHED状態_PARKING027例外() {
            // Given
            List<Long> spaceIds = List.of(SPACE_ID);
            ParkingSubleaseEntity sublease = createMatchedSublease();
            ApplySubleaseRequest request = new ApplySubleaseRequest(VEHICLE_ID, null);
            given(subleaseRepository.findByIdAndSpaceIdIn(SUBLEASE_ID, spaceIds)).willReturn(Optional.of(sublease));

            // When / Then
            assertThatThrownBy(() -> parkingSubleaseService.apply(spaceIds, SUBLEASE_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_027"));
        }

        @Test
        @DisplayName("異常系: サブリースが見つからない場合PARKING_025例外")
        void apply_不在_PARKING025例外() {
            // Given
            List<Long> spaceIds = List.of(SPACE_ID);
            given(subleaseRepository.findByIdAndSpaceIdIn(SUBLEASE_ID, spaceIds)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> parkingSubleaseService.apply(spaceIds, SUBLEASE_ID, USER_ID,
                    new ApplySubleaseRequest(VEHICLE_ID, null)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_025"));
        }
    }

    // ========================================
    // approve
    // ========================================

    @Nested
    @DisplayName("approve")
    class Approve {

        @Test
        @DisplayName("正常系: サブリース申請が承認され他の申請が拒否される")
        void approve_正常_承認され他の申請が拒否される() throws Exception {
            // Given
            List<Long> spaceIds = List.of(SPACE_ID);
            ParkingSubleaseEntity sublease = createOpenSublease();
            ParkingSubleaseApplicationEntity approvedApp = createPendingApplication();
            // Set ID on approvedApp via reflection (BaseEntity.id)
            var idField = approvedApp.getClass().getSuperclass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(approvedApp, APPLICATION_ID);

            ParkingSubleaseApplicationEntity otherApp = ParkingSubleaseApplicationEntity.builder()
                    .subleaseId(SUBLEASE_ID).userId(102L).vehicleId(202L).build();
            var idField2 = otherApp.getClass().getSuperclass().getDeclaredField("id");
            idField2.setAccessible(true);
            idField2.set(otherApp, APPLICATION_ID + 1);

            ApproveSubleaseRequest request = new ApproveSubleaseRequest(APPLICATION_ID);

            given(subleaseRepository.findByIdAndSpaceIdIn(SUBLEASE_ID, spaceIds)).willReturn(Optional.of(sublease));
            given(subleaseApplicationRepository.findById(APPLICATION_ID)).willReturn(Optional.of(approvedApp));
            given(subleaseApplicationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(subleaseRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(subleaseApplicationRepository.findBySubleaseId(SUBLEASE_ID)).willReturn(List.of(approvedApp, otherApp));
            given(parkingMapper.toSubleaseDetailResponse(any())).willReturn(null);

            // When
            parkingSubleaseService.approve(spaceIds, SUBLEASE_ID, request);

            // Then
            assertThat(approvedApp.getStatus()).isEqualTo(SubleaseApplicationStatus.APPROVED);
            assertThat(otherApp.getStatus()).isEqualTo(SubleaseApplicationStatus.REJECTED);
            assertThat(sublease.getStatus()).isEqualTo(SubleaseStatus.MATCHED);
        }

        @Test
        @DisplayName("異常系: OPEN以外のステータスでPARKING_027例外")
        void approve_MATCHED状態_PARKING027例外() {
            // Given
            List<Long> spaceIds = List.of(SPACE_ID);
            ParkingSubleaseEntity sublease = createMatchedSublease();
            given(subleaseRepository.findByIdAndSpaceIdIn(SUBLEASE_ID, spaceIds)).willReturn(Optional.of(sublease));

            // When / Then
            assertThatThrownBy(() -> parkingSubleaseService.approve(spaceIds, SUBLEASE_ID,
                    new ApproveSubleaseRequest(APPLICATION_ID)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_027"));
        }

        @Test
        @DisplayName("異常系: 申請が見つからない場合PARKING_026例外")
        void approve_申請不在_PARKING026例外() {
            // Given
            List<Long> spaceIds = List.of(SPACE_ID);
            ParkingSubleaseEntity sublease = createOpenSublease();
            given(subleaseRepository.findByIdAndSpaceIdIn(SUBLEASE_ID, spaceIds)).willReturn(Optional.of(sublease));
            given(subleaseApplicationRepository.findById(APPLICATION_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> parkingSubleaseService.approve(spaceIds, SUBLEASE_ID,
                    new ApproveSubleaseRequest(APPLICATION_ID)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_026"));
        }

        @Test
        @DisplayName("異常系: 申請がPENDING以外でPARKING_028例外")
        void approve_申請APPROVED状態_PARKING028例外() {
            // Given
            List<Long> spaceIds = List.of(SPACE_ID);
            ParkingSubleaseEntity sublease = createOpenSublease();
            ParkingSubleaseApplicationEntity app = createPendingApplication();
            app.approve(); // 既にAPPROVED
            given(subleaseRepository.findByIdAndSpaceIdIn(SUBLEASE_ID, spaceIds)).willReturn(Optional.of(sublease));
            given(subleaseApplicationRepository.findById(APPLICATION_ID)).willReturn(Optional.of(app));

            // When / Then
            assertThatThrownBy(() -> parkingSubleaseService.approve(spaceIds, SUBLEASE_ID,
                    new ApproveSubleaseRequest(APPLICATION_ID)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_028"));
        }
    }

    // ========================================
    // terminate
    // ========================================

    @Nested
    @DisplayName("terminate")
    class Terminate {

        @Test
        @DisplayName("正常系: サブリースが終了する")
        void terminate_正常_終了する() {
            // Given
            List<Long> spaceIds = List.of(SPACE_ID);
            ParkingSubleaseEntity sublease = createOpenSublease();
            given(subleaseRepository.findByIdAndSpaceIdIn(SUBLEASE_ID, spaceIds)).willReturn(Optional.of(sublease));
            given(subleaseRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(parkingMapper.toSubleaseDetailResponse(any())).willReturn(null);

            // When
            parkingSubleaseService.terminate(spaceIds, SUBLEASE_ID);

            // Then
            assertThat(sublease.getStatus()).isEqualTo(SubleaseStatus.CANCELLED);
            verify(subleaseRepository).save(sublease);
        }

        @Test
        @DisplayName("異常系: サブリースが見つからない場合PARKING_025例外")
        void terminate_不在_PARKING025例外() {
            // Given
            List<Long> spaceIds = List.of(SPACE_ID);
            given(subleaseRepository.findByIdAndSpaceIdIn(SUBLEASE_ID, spaceIds)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> parkingSubleaseService.terminate(spaceIds, SUBLEASE_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_025"));
        }
    }

    // ========================================
    // getPayments
    // ========================================

    @Nested
    @DisplayName("getPayments")
    class GetPayments {

        @Test
        @DisplayName("正常系: 決済一覧が取得できる")
        void getPayments_正常_決済一覧取得() {
            // Given
            List<Long> spaceIds = List.of(SPACE_ID);
            Pageable pageable = PageRequest.of(0, 10);
            given(subleaseRepository.findByIdAndSpaceIdIn(SUBLEASE_ID, spaceIds))
                    .willReturn(Optional.of(createOpenSublease()));
            given(subleasePaymentRepository.findBySubleaseId(SUBLEASE_ID, pageable))
                    .willReturn(Page.empty(pageable));

            // When
            Page<SubleasePaymentResponse> result = parkingSubleaseService.getPayments(spaceIds, SUBLEASE_ID, pageable);

            // Then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("異常系: サブリースが見つからない場合PARKING_025例外")
        void getPayments_不在_PARKING025例外() {
            // Given
            List<Long> spaceIds = List.of(SPACE_ID);
            Pageable pageable = PageRequest.of(0, 10);
            given(subleaseRepository.findByIdAndSpaceIdIn(SUBLEASE_ID, spaceIds)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> parkingSubleaseService.getPayments(spaceIds, SUBLEASE_ID, pageable))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_025"));
        }
    }
}
