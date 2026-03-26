package com.mannschaft.app.parking;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.parking.dto.CreateSpaceRequest;
import com.mannschaft.app.parking.dto.SpaceResponse;
import com.mannschaft.app.parking.entity.ParkingSpaceEntity;
import com.mannschaft.app.parking.repository.*;
import com.mannschaft.app.parking.service.ParkingSpaceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
    }
}
