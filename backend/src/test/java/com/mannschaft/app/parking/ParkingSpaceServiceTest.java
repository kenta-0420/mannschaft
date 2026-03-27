package com.mannschaft.app.parking;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.parking.dto.*;
import com.mannschaft.app.parking.entity.ParkingAssignmentEntity;
import com.mannschaft.app.parking.entity.ParkingSpaceEntity;
import com.mannschaft.app.parking.entity.ParkingSpacePriceHistoryEntity;
import com.mannschaft.app.parking.repository.*;
import com.mannschaft.app.parking.service.ParkingSpaceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("ParkingSpaceService 単体テスト")
class ParkingSpaceServiceTest {

    @Mock private ParkingSpaceRepository spaceRepository;
    @Mock private ParkingAssignmentRepository assignmentRepository;
    @Mock private ParkingApplicationRepository applicationRepository;
    @Mock private ParkingListingRepository listingRepository;
    @Mock private ParkingSubleaseRepository subleaseRepository;
    @Mock private ParkingSpacePriceHistoryRepository priceHistoryRepository;
    @Mock private ParkingMapper parkingMapper;
    @InjectMocks private ParkingSpaceService service;

    private static final String SCOPE_TYPE = "TEAM";
    private static final Long SCOPE_ID = 1L;
    private static final Long USER_ID = 100L;

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("正常系: 区画が作成される")
        void 作成_正常_保存() {
            // Given
            CreateSpaceRequest req = new CreateSpaceRequest("A-001", "INDOOR", null,
                    BigDecimal.valueOf(10000), "1F", null);
            given(spaceRepository.save(any(ParkingSpaceEntity.class))).willAnswer(inv -> inv.getArgument(0));
            given(parkingMapper.toSpaceResponse(any(ParkingSpaceEntity.class)))
                    .willReturn(new SpaceResponse(1L, SCOPE_TYPE, SCOPE_ID, "A-001", "INDOOR",
                            null, BigDecimal.valueOf(10000), "VACANT", "1F", null, null, null, null, null, null));

            // When
            SpaceResponse result = service.create(SCOPE_TYPE, SCOPE_ID, req, USER_ID);

            // Then
            assertThat(result.getSpaceNumber()).isEqualTo("A-001");
            verify(spaceRepository).save(any(ParkingSpaceEntity.class));
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("異常系: 占有中の区画は削除不可でPARKING_009例外")
        void 削除_占有中_例外() {
            // Given
            ParkingSpaceEntity entity = ParkingSpaceEntity.builder()
                    .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID).spaceNumber("A-001")
                    .spaceType(SpaceType.INDOOR).build();
            try {
                var field = ParkingSpaceEntity.class.getDeclaredField("status");
                field.setAccessible(true);
                field.set(entity, SpaceStatus.OCCUPIED);
            } catch (Exception ignored) {}
            given(spaceRepository.findByIdAndScopeTypeAndScopeId(1L, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));

            // When / Then
            assertThatThrownBy(() -> service.delete(SCOPE_TYPE, SCOPE_ID, 1L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_009"));
        }

        @Test
        @DisplayName("異常系: 区画不在でPARKING_001例外")
        void 削除_不在_例外() {
            // Given
            given(spaceRepository.findByIdAndScopeTypeAndScopeId(1L, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.delete(SCOPE_TYPE, SCOPE_ID, 1L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_001"));
        }

        @Test
        @DisplayName("正常系: 空き区画は削除できる")
        void 削除_正常_保存() {
            // Given
            ParkingSpaceEntity entity = ParkingSpaceEntity.builder()
                    .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID).spaceNumber("A-001")
                    .spaceType(SpaceType.INDOOR).build();
            given(spaceRepository.findByIdAndScopeTypeAndScopeId(1L, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(spaceRepository.save(any())).willReturn(entity);

            // When
            service.delete(SCOPE_TYPE, SCOPE_ID, 1L);

            // Then
            verify(spaceRepository).save(any());
        }
    }

    @Nested
    @DisplayName("update")
    class Update {

        @Test
        @DisplayName("正常系: 区画が更新される（料金変更なし）")
        void 更新_正常_料金変更なし() {
            // Given
            ParkingSpaceEntity entity = ParkingSpaceEntity.builder()
                    .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID).spaceNumber("A-001")
                    .spaceType(SpaceType.INDOOR).pricePerMonth(BigDecimal.valueOf(10000))
                    .applicationStatus(ApplicationStatus.NOT_ACCEPTING).createdBy(USER_ID).build();
            UpdateSpaceRequest req = new UpdateSpaceRequest("A-001-updated", "INDOOR", null,
                    BigDecimal.valueOf(10000), "2F", null);
            given(spaceRepository.findByIdAndScopeTypeAndScopeId(1L, SCOPE_TYPE, SCOPE_ID)).willReturn(Optional.of(entity));
            given(spaceRepository.save(any())).willReturn(entity);
            given(parkingMapper.toSpaceResponse(any())).willReturn(
                    new SpaceResponse(1L, SCOPE_TYPE, SCOPE_ID, "A-001-updated", "INDOOR",
                            null, BigDecimal.valueOf(10000), "VACANT", "2F", null, "NOT_ACCEPTING", null, null, null, null));

            // When
            SpaceResponse result = service.update(SCOPE_TYPE, SCOPE_ID, 1L, req, USER_ID);

            // Then
            assertThat(result.getSpaceNumber()).isEqualTo("A-001-updated");
            verify(spaceRepository).save(any());
        }

        @Test
        @DisplayName("正常系: 区画が更新される（料金変更あり）")
        void 更新_正常_料金変更あり() {
            // Given
            ParkingSpaceEntity entity = ParkingSpaceEntity.builder()
                    .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID).spaceNumber("A-001")
                    .spaceType(SpaceType.INDOOR).pricePerMonth(BigDecimal.valueOf(10000))
                    .applicationStatus(ApplicationStatus.NOT_ACCEPTING).createdBy(USER_ID).build();
            UpdateSpaceRequest req = new UpdateSpaceRequest("A-001", "INDOOR", null,
                    BigDecimal.valueOf(12000), null, null);
            given(spaceRepository.findByIdAndScopeTypeAndScopeId(1L, SCOPE_TYPE, SCOPE_ID)).willReturn(Optional.of(entity));
            given(spaceRepository.save(any())).willReturn(entity);
            given(priceHistoryRepository.save(any())).willReturn(ParkingSpacePriceHistoryEntity.builder()
                    .spaceId(1L).changedBy(USER_ID).build());
            given(parkingMapper.toSpaceResponse(any())).willReturn(
                    new SpaceResponse(1L, SCOPE_TYPE, SCOPE_ID, "A-001", "INDOOR",
                            null, BigDecimal.valueOf(12000), "VACANT", null, null, "NOT_ACCEPTING", null, null, null, null));

            // When
            SpaceResponse result = service.update(SCOPE_TYPE, SCOPE_ID, 1L, req, USER_ID);

            // Then
            assertThat(result.getPricePerMonth()).isEqualByComparingTo(BigDecimal.valueOf(12000));
            verify(priceHistoryRepository).save(any());
        }
    }

    @Nested
    @DisplayName("toggleMaintenance")
    class ToggleMaintenance {

        @Test
        @DisplayName("正常系: メンテナンス開始")
        void メンテナンス_開始_正常() {
            // Given
            ParkingSpaceEntity entity = ParkingSpaceEntity.builder()
                    .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID).spaceNumber("A-001")
                    .spaceType(SpaceType.INDOOR).applicationStatus(ApplicationStatus.NOT_ACCEPTING).createdBy(USER_ID).build();
            given(spaceRepository.findByIdAndScopeTypeAndScopeId(1L, SCOPE_TYPE, SCOPE_ID)).willReturn(Optional.of(entity));
            given(spaceRepository.save(any())).willReturn(entity);
            given(parkingMapper.toSpaceResponse(any())).willReturn(
                    new SpaceResponse(1L, SCOPE_TYPE, SCOPE_ID, "A-001", "INDOOR",
                            null, null, "MAINTENANCE", null, null, "NOT_ACCEPTING", null, null, null, null));

            // When
            SpaceResponse result = service.toggleMaintenance(SCOPE_TYPE, SCOPE_ID, 1L,
                    new MaintenanceToggleRequest(true));

            // Then
            assertThat(result.getStatus()).isEqualTo("MAINTENANCE");
        }

        @Test
        @DisplayName("異常系: 占有中の区画はメンテナンス開始不可")
        void メンテナンス_占有中_例外() {
            // Given
            ParkingSpaceEntity entity = ParkingSpaceEntity.builder()
                    .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID).spaceNumber("A-001")
                    .spaceType(SpaceType.INDOOR).build();
            try {
                var field = ParkingSpaceEntity.class.getDeclaredField("status");
                field.setAccessible(true);
                field.set(entity, SpaceStatus.OCCUPIED);
            } catch (Exception ignored) {}
            given(spaceRepository.findByIdAndScopeTypeAndScopeId(1L, SCOPE_TYPE, SCOPE_ID)).willReturn(Optional.of(entity));

            // When / Then
            assertThatThrownBy(() -> service.toggleMaintenance(SCOPE_TYPE, SCOPE_ID, 1L,
                    new MaintenanceToggleRequest(true)))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("正常系: メンテナンス終了")
        void メンテナンス_終了_正常() {
            // Given
            ParkingSpaceEntity entity = ParkingSpaceEntity.builder()
                    .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID).spaceNumber("A-001")
                    .spaceType(SpaceType.INDOOR).applicationStatus(ApplicationStatus.NOT_ACCEPTING).createdBy(USER_ID).build();
            try {
                var field = ParkingSpaceEntity.class.getDeclaredField("status");
                field.setAccessible(true);
                field.set(entity, SpaceStatus.MAINTENANCE);
            } catch (Exception ignored) {}
            given(spaceRepository.findByIdAndScopeTypeAndScopeId(1L, SCOPE_TYPE, SCOPE_ID)).willReturn(Optional.of(entity));
            given(spaceRepository.save(any())).willReturn(entity);
            given(parkingMapper.toSpaceResponse(any())).willReturn(
                    new SpaceResponse(1L, SCOPE_TYPE, SCOPE_ID, "A-001", "INDOOR",
                            null, null, "VACANT", null, null, "NOT_ACCEPTING", null, null, null, null));

            // When
            SpaceResponse result = service.toggleMaintenance(SCOPE_TYPE, SCOPE_ID, 1L,
                    new MaintenanceToggleRequest(false));

            // Then
            assertThat(result.getStatus()).isEqualTo("VACANT");
        }
    }

    @Nested
    @DisplayName("acceptApplications")
    class AcceptApplications {

        @Test
        @DisplayName("正常系: 申請受付開始")
        void 申請受付_正常_開始() {
            // Given
            ParkingSpaceEntity entity = ParkingSpaceEntity.builder()
                    .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID).spaceNumber("A-001")
                    .spaceType(SpaceType.INDOOR).applicationStatus(ApplicationStatus.NOT_ACCEPTING).createdBy(USER_ID).build();
            given(spaceRepository.findByIdAndScopeTypeAndScopeId(1L, SCOPE_TYPE, SCOPE_ID)).willReturn(Optional.of(entity));
            given(spaceRepository.save(any())).willReturn(entity);
            given(parkingMapper.toSpaceResponse(any())).willReturn(
                    new SpaceResponse(1L, SCOPE_TYPE, SCOPE_ID, "A-001", "INDOOR",
                            null, null, "VACANT", null, null, "ACCEPTING", "LOTTERY", null, null, null));

            // When
            SpaceResponse result = service.acceptApplications(SCOPE_TYPE, SCOPE_ID, 1L,
                    new AcceptApplicationsRequest("LOTTERY", null));

            // Then
            assertThat(result.getApplicationStatus()).isEqualTo("ACCEPTING");
        }

        @Test
        @DisplayName("異常系: 占有中の区画は申請受付開始不可")
        void 申請受付_占有中_例外() {
            // Given
            ParkingSpaceEntity entity = ParkingSpaceEntity.builder()
                    .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID).spaceNumber("A-001")
                    .spaceType(SpaceType.INDOOR).build();
            try {
                var field = ParkingSpaceEntity.class.getDeclaredField("status");
                field.setAccessible(true);
                field.set(entity, SpaceStatus.OCCUPIED);
            } catch (Exception ignored) {}
            given(spaceRepository.findByIdAndScopeTypeAndScopeId(1L, SCOPE_TYPE, SCOPE_ID)).willReturn(Optional.of(entity));

            // When / Then
            assertThatThrownBy(() -> service.acceptApplications(SCOPE_TYPE, SCOPE_ID, 1L,
                    new AcceptApplicationsRequest("LOTTERY", null)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_011"));
        }
    }

    @Nested
    @DisplayName("getStats")
    class GetStats {

        @Test
        @DisplayName("正常系: 統計が返される")
        void 統計_正常_取得() {
            // Given
            given(spaceRepository.countByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID)).willReturn(10L);
            given(spaceRepository.countByScopeTypeAndScopeIdAndStatus(SCOPE_TYPE, SCOPE_ID, SpaceStatus.VACANT)).willReturn(5L);
            given(spaceRepository.countByScopeTypeAndScopeIdAndStatus(SCOPE_TYPE, SCOPE_ID, SpaceStatus.OCCUPIED)).willReturn(3L);
            given(spaceRepository.countByScopeTypeAndScopeIdAndStatus(SCOPE_TYPE, SCOPE_ID, SpaceStatus.MAINTENANCE)).willReturn(2L);
            given(spaceRepository.findByScopeTypeAndScopeId(eq(SCOPE_TYPE), eq(SCOPE_ID), any(Pageable.class)))
                    .willReturn(Page.empty());

            // When
            ParkingStatsResponse result = service.getStats(SCOPE_TYPE, SCOPE_ID);

            // Then
            assertThat(result.getTotalSpaces()).isEqualTo(10L);
            assertThat(result.getVacantSpaces()).isEqualTo(5L);
            assertThat(result.getOccupiedSpaces()).isEqualTo(3L);
        }
    }

    @Nested
    @DisplayName("listVacant")
    class ListVacant {

        @Test
        @DisplayName("正常系: 空き区画一覧が返される")
        void 空き区画一覧_正常_取得() {
            // Given
            ParkingSpaceEntity entity = ParkingSpaceEntity.builder()
                    .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID).spaceNumber("A-001")
                    .spaceType(SpaceType.INDOOR).applicationStatus(ApplicationStatus.NOT_ACCEPTING).createdBy(USER_ID).build();
            given(spaceRepository.findByScopeTypeAndScopeIdAndStatus(SCOPE_TYPE, SCOPE_ID, SpaceStatus.VACANT))
                    .willReturn(List.of(entity));
            given(parkingMapper.toSpaceResponseList(any())).willReturn(
                    List.of(new SpaceResponse(1L, SCOPE_TYPE, SCOPE_ID, "A-001", "INDOOR",
                            null, null, "VACANT", null, null, "NOT_ACCEPTING", null, null, null, null)));

            // When
            List<SpaceResponse> result = service.listVacant(SCOPE_TYPE, SCOPE_ID);

            // Then
            assertThat(result).hasSize(1);
        }
    }
}
