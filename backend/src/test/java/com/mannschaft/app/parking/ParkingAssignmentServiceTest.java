package com.mannschaft.app.parking;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.parking.dto.AssignRequest;
import com.mannschaft.app.parking.dto.AssignmentResponse;
import com.mannschaft.app.parking.dto.BulkAssignRequest;
import com.mannschaft.app.parking.entity.ParkingAssignmentEntity;
import com.mannschaft.app.parking.entity.ParkingSettingsEntity;
import com.mannschaft.app.parking.entity.ParkingSpaceEntity;
import com.mannschaft.app.parking.repository.ParkingAssignmentRepository;
import com.mannschaft.app.parking.repository.ParkingSettingsRepository;
import com.mannschaft.app.parking.repository.ParkingSpaceRepository;
import com.mannschaft.app.parking.service.ParkingAssignmentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("ParkingAssignmentService 単体テスト")
class ParkingAssignmentServiceTest {

    @Mock private ParkingSpaceRepository spaceRepository;
    @Mock private ParkingAssignmentRepository assignmentRepository;
    @Mock private ParkingSettingsRepository settingsRepository;
    @Mock private ParkingMapper parkingMapper;
    @InjectMocks private ParkingAssignmentService service;

    private static final String SCOPE_TYPE = "TEAM";
    private static final Long SCOPE_ID = 1L;
    private static final Long USER_ID = 100L;

    @Nested
    @DisplayName("assign")
    class Assign {

        @Test
        @DisplayName("異常系: 区画が空きでない場合PARKING_011例外")
        void 割当_空きでない_例外() {
            // Given
            ParkingSpaceEntity space = ParkingSpaceEntity.builder()
                    .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID).spaceNumber("A-001")
                    .spaceType(SpaceType.INDOOR).build();
            try {
                var field = ParkingSpaceEntity.class.getDeclaredField("status");
                field.setAccessible(true);
                field.set(space, SpaceStatus.OCCUPIED);
            } catch (Exception ignored) {}
            given(spaceRepository.findByIdAndScopeTypeAndScopeId(1L, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(space));

            AssignRequest req = new AssignRequest(USER_ID, 1L, LocalDate.now(), null);

            // When / Then
            assertThatThrownBy(() -> service.assign(SCOPE_TYPE, SCOPE_ID, 1L, req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_011"));
        }
    }

    @Nested
    @DisplayName("bulkAssign")
    class BulkAssign {

        @Test
        @DisplayName("異常系: 50件超過でPARKING_030例外")
        void 一括_上限超過_例外() {
            // Given
            List<BulkAssignRequest.BulkAssignItem> items = new java.util.ArrayList<>();
            for (int i = 0; i < 51; i++) {
                items.add(new BulkAssignRequest.BulkAssignItem((long) i, USER_ID, 1L, LocalDate.now(), null));
            }
            BulkAssignRequest req = new BulkAssignRequest(items);

            // When / Then
            assertThatThrownBy(() -> service.bulkAssign(SCOPE_TYPE, SCOPE_ID, req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_030"));
        }
    }
}
