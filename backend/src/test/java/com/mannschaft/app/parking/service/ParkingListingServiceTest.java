package com.mannschaft.app.parking.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.parking.ListingStatus;
import com.mannschaft.app.parking.ParkingMapper;
import com.mannschaft.app.parking.dto.ApplicationResponse;
import com.mannschaft.app.parking.dto.CreateListingRequest;
import com.mannschaft.app.parking.dto.ListingApplyRequest;
import com.mannschaft.app.parking.dto.ListingDetailResponse;
import com.mannschaft.app.parking.dto.ListingResponse;
import com.mannschaft.app.parking.dto.UpdateListingRequest;
import com.mannschaft.app.parking.entity.ParkingApplicationEntity;
import com.mannschaft.app.parking.entity.ParkingAssignmentEntity;
import com.mannschaft.app.parking.entity.ParkingListingEntity;
import com.mannschaft.app.parking.repository.ParkingApplicationRepository;
import com.mannschaft.app.parking.repository.ParkingAssignmentRepository;
import com.mannschaft.app.parking.repository.ParkingListingRepository;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link ParkingListingService} の単体テスト。
 * 譲渡希望のCRUD・申込・譲渡確定ロジックを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ParkingListingService 単体テスト")
class ParkingListingServiceTest {

    @Mock
    private ParkingListingRepository listingRepository;

    @Mock
    private ParkingAssignmentRepository assignmentRepository;

    @Mock
    private ParkingApplicationRepository applicationRepository;

    @Mock
    private ParkingMapper parkingMapper;

    @InjectMocks
    private ParkingListingService parkingListingService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long SPACE_ID = 1L;
    private static final Long USER_ID = 100L;
    private static final Long ASSIGNMENT_ID = 50L;
    private static final Long LISTING_ID = 10L;
    private static final Long VEHICLE_ID = 200L;

    private ParkingAssignmentEntity createAssignment(Long userId) {
        return ParkingAssignmentEntity.builder()
                .spaceId(SPACE_ID)
                .userId(userId)
                .assignedBy(1L)
                .build();
    }

    private ParkingListingEntity createOpenListing() {
        return ParkingListingEntity.builder()
                .spaceId(SPACE_ID)
                .assignmentId(ASSIGNMENT_ID)
                .listedBy(USER_ID)
                .reason("引越し予定")
                .desiredTransferDate(LocalDate.of(2026, 5, 1))
                .build();
    }

    private ParkingListingEntity createReservedListing() {
        ParkingListingEntity entity = createOpenListing();
        entity.reserve(101L, 201L);
        return entity;
    }

    private ListingResponse createListingResponse() {
        return new ListingResponse(LISTING_ID, SPACE_ID, ASSIGNMENT_ID, USER_ID,
                "引越し予定", LocalDate.of(2026, 5, 1), "OPEN", null, null);
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
            Page<ListingResponse> result = parkingListingService.list(List.of(), null, pageable);

            // Then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("正常系: ステータス指定ありで絞り込みできる")
        void list_ステータス指定あり_絞り込み取得() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            List<Long> spaceIds = List.of(SPACE_ID);
            ParkingListingEntity entity = createOpenListing();
            given(listingRepository.findBySpaceIdInAndStatus(spaceIds, ListingStatus.OPEN, pageable))
                    .willReturn(new PageImpl<>(List.of(entity)));
            given(parkingMapper.toListingResponse(entity)).willReturn(createListingResponse());

            // When
            Page<ListingResponse> result = parkingListingService.list(spaceIds, "OPEN", pageable);

            // Then
            assertThat(result.getContent()).hasSize(1);
        }

        @Test
        @DisplayName("正常系: ステータス指定なしで全件取得できる")
        void list_ステータス指定なし_全件取得() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            List<Long> spaceIds = List.of(SPACE_ID);
            given(listingRepository.findBySpaceIdIn(spaceIds, pageable))
                    .willReturn(new PageImpl<>(List.of(createOpenListing())));
            given(parkingMapper.toListingResponse(any())).willReturn(createListingResponse());

            // When
            Page<ListingResponse> result = parkingListingService.list(spaceIds, null, pageable);

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
        @DisplayName("正常系: 譲渡希望が作成される")
        void create_正常_譲渡希望が作成される() {
            // Given
            List<Long> spaceIds = List.of(SPACE_ID);
            CreateListingRequest request = new CreateListingRequest(SPACE_ID, "引越し予定", LocalDate.of(2026, 5, 1));
            ParkingAssignmentEntity assignment = createAssignment(USER_ID);
            given(assignmentRepository.findBySpaceIdAndReleasedAtIsNull(SPACE_ID)).willReturn(Optional.of(assignment));
            given(listingRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(parkingMapper.toListingResponse(any())).willReturn(createListingResponse());

            // When
            ListingResponse result = parkingListingService.create(spaceIds, USER_ID, request);

            // Then
            assertThat(result).isNotNull();
            verify(listingRepository).save(any(ParkingListingEntity.class));
        }

        @Test
        @DisplayName("異常系: スコープ不一致でPARKING_020例外")
        void create_スコープ不一致_PARKING020例外() {
            // Given
            List<Long> spaceIds = List.of(999L);
            CreateListingRequest request = new CreateListingRequest(SPACE_ID, "理由", null);

            // When / Then
            assertThatThrownBy(() -> parkingListingService.create(spaceIds, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_020"));
        }

        @Test
        @DisplayName("異常系: 割り当てが見つからない場合PARKING_003例外")
        void create_割り当て不在_PARKING003例外() {
            // Given
            List<Long> spaceIds = List.of(SPACE_ID);
            CreateListingRequest request = new CreateListingRequest(SPACE_ID, "理由", null);
            given(assignmentRepository.findBySpaceIdAndReleasedAtIsNull(SPACE_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> parkingListingService.create(spaceIds, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_003"));
        }

        @Test
        @DisplayName("異常系: 自分の割り当てでない場合PARKING_031例外")
        void create_他人の割り当て_PARKING031例外() {
            // Given
            List<Long> spaceIds = List.of(SPACE_ID);
            CreateListingRequest request = new CreateListingRequest(SPACE_ID, "理由", null);
            ParkingAssignmentEntity assignment = createAssignment(999L); // 他人のID
            given(assignmentRepository.findBySpaceIdAndReleasedAtIsNull(SPACE_ID)).willReturn(Optional.of(assignment));

            // When / Then
            assertThatThrownBy(() -> parkingListingService.create(spaceIds, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_031"));
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
            ParkingListingEntity entity = createOpenListing();
            given(listingRepository.findByIdAndSpaceIdIn(LISTING_ID, spaceIds)).willReturn(Optional.of(entity));
            given(parkingMapper.toListingDetailResponse(entity)).willReturn(null);

            // When
            parkingListingService.getDetail(spaceIds, LISTING_ID);

            // Then
            verify(listingRepository).findByIdAndSpaceIdIn(LISTING_ID, spaceIds);
        }

        @Test
        @DisplayName("異常系: 譲渡希望が見つからない場合PARKING_005例外")
        void getDetail_不在_PARKING005例外() {
            // Given
            List<Long> spaceIds = List.of(SPACE_ID);
            given(listingRepository.findByIdAndSpaceIdIn(LISTING_ID, spaceIds)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> parkingListingService.getDetail(spaceIds, LISTING_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_005"));
        }
    }

    // ========================================
    // update
    // ========================================

    @Nested
    @DisplayName("update")
    class Update {

        @Test
        @DisplayName("正常系: 譲渡希望が更新される")
        void update_正常_更新される() {
            // Given
            List<Long> spaceIds = List.of(SPACE_ID);
            ParkingListingEntity entity = createOpenListing();
            UpdateListingRequest request = new UpdateListingRequest("新しい理由", LocalDate.of(2026, 6, 1));
            given(listingRepository.findByIdAndSpaceIdIn(LISTING_ID, spaceIds)).willReturn(Optional.of(entity));
            given(listingRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(parkingMapper.toListingResponse(any())).willReturn(createListingResponse());

            // When
            ListingResponse result = parkingListingService.update(spaceIds, LISTING_ID, request);

            // Then
            assertThat(result).isNotNull();
            assertThat(entity.getReason()).isEqualTo("新しい理由");
        }

        @Test
        @DisplayName("異常系: OPEN以外のステータスでPARKING_022例外")
        void update_RESERVED状態_PARKING022例外() {
            // Given
            List<Long> spaceIds = List.of(SPACE_ID);
            ParkingListingEntity entity = createReservedListing();
            UpdateListingRequest request = new UpdateListingRequest("理由", null);
            given(listingRepository.findByIdAndSpaceIdIn(LISTING_ID, spaceIds)).willReturn(Optional.of(entity));

            // When / Then
            assertThatThrownBy(() -> parkingListingService.update(spaceIds, LISTING_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_022"));
        }
    }

    // ========================================
    // delete
    // ========================================

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("正常系: 譲渡希望が論理削除される")
        void delete_正常_論理削除() {
            // Given
            List<Long> spaceIds = List.of(SPACE_ID);
            ParkingListingEntity entity = createOpenListing();
            given(listingRepository.findByIdAndSpaceIdIn(LISTING_ID, spaceIds)).willReturn(Optional.of(entity));

            // When
            parkingListingService.delete(spaceIds, LISTING_ID);

            // Then
            assertThat(entity.getDeletedAt()).isNotNull();
            verify(listingRepository).save(entity);
        }

        @Test
        @DisplayName("異常系: 譲渡希望が見つからない場合PARKING_005例外")
        void delete_不在_PARKING005例外() {
            // Given
            List<Long> spaceIds = List.of(SPACE_ID);
            given(listingRepository.findByIdAndSpaceIdIn(LISTING_ID, spaceIds)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> parkingListingService.delete(spaceIds, LISTING_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_005"));
        }
    }

    // ========================================
    // apply
    // ========================================

    @Nested
    @DisplayName("apply")
    class Apply {

        @Test
        @DisplayName("正常系: 譲渡希望に申し込みできる")
        void apply_正常_申し込みできる() {
            // Given
            List<Long> spaceIds = List.of(SPACE_ID);
            ParkingListingEntity listing = createOpenListing();
            ListingApplyRequest request = new ListingApplyRequest(VEHICLE_ID, "申込メッセージ");
            given(listingRepository.findByIdAndSpaceIdIn(LISTING_ID, spaceIds)).willReturn(Optional.of(listing));
            given(applicationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(parkingMapper.toApplicationResponse(any())).willReturn(
                    new ApplicationResponse(1L, SPACE_ID, USER_ID, VEHICLE_ID, "LISTING", LISTING_ID,
                            "PENDING", 0, "申込メッセージ", null, null, null, null));

            // When
            ApplicationResponse result = parkingListingService.apply(spaceIds, LISTING_ID, USER_ID, request);

            // Then
            assertThat(result).isNotNull();
            verify(applicationRepository).save(any(ParkingApplicationEntity.class));
        }

        @Test
        @DisplayName("異常系: OPEN以外のステータスでPARKING_022例外")
        void apply_RESERVED状態_PARKING022例外() {
            // Given
            List<Long> spaceIds = List.of(SPACE_ID);
            ParkingListingEntity listing = createReservedListing();
            ListingApplyRequest request = new ListingApplyRequest(VEHICLE_ID, null);
            given(listingRepository.findByIdAndSpaceIdIn(LISTING_ID, spaceIds)).willReturn(Optional.of(listing));

            // When / Then
            assertThatThrownBy(() -> parkingListingService.apply(spaceIds, LISTING_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_022"));
        }

        @Test
        @DisplayName("異常系: 譲渡希望が見つからない場合PARKING_005例外")
        void apply_不在_PARKING005例外() {
            // Given
            List<Long> spaceIds = List.of(SPACE_ID);
            given(listingRepository.findByIdAndSpaceIdIn(LISTING_ID, spaceIds)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> parkingListingService.apply(spaceIds, LISTING_ID, USER_ID,
                    new ListingApplyRequest(VEHICLE_ID, null)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_005"));
        }
    }

    // ========================================
    // transfer
    // ========================================

    @Nested
    @DisplayName("transfer")
    class Transfer {

        @Test
        @DisplayName("正常系: 譲渡が確定する")
        void transfer_正常_譲渡確定() {
            // Given
            List<Long> spaceIds = List.of(SPACE_ID);
            ParkingListingEntity listing = createReservedListing();
            given(listingRepository.findByIdAndSpaceIdIn(LISTING_ID, spaceIds)).willReturn(Optional.of(listing));
            given(listingRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(parkingMapper.toListingDetailResponse(any())).willReturn(null);

            // When
            parkingListingService.transfer(spaceIds, LISTING_ID);

            // Then
            assertThat(listing.getStatus()).isEqualTo(ListingStatus.TRANSFERRED);
            verify(listingRepository).save(listing);
        }

        @Test
        @DisplayName("異常系: RESERVED以外のステータスでPARKING_022例外")
        void transfer_OPEN状態_PARKING022例外() {
            // Given
            List<Long> spaceIds = List.of(SPACE_ID);
            ParkingListingEntity listing = createOpenListing(); // OPEN状態
            given(listingRepository.findByIdAndSpaceIdIn(LISTING_ID, spaceIds)).willReturn(Optional.of(listing));

            // When / Then
            assertThatThrownBy(() -> parkingListingService.transfer(spaceIds, LISTING_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_022"));
        }

        @Test
        @DisplayName("異常系: 譲渡希望が見つからない場合PARKING_005例外")
        void transfer_不在_PARKING005例外() {
            // Given
            List<Long> spaceIds = List.of(SPACE_ID);
            given(listingRepository.findByIdAndSpaceIdIn(LISTING_ID, spaceIds)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> parkingListingService.transfer(spaceIds, LISTING_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_005"));
        }
    }
}
