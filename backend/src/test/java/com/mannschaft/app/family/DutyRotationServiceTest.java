package com.mannschaft.app.family;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.family.dto.DutyRotationRequest;
import com.mannschaft.app.family.dto.DutyRotationResponse;
import com.mannschaft.app.family.entity.DutyRotationEntity;
import com.mannschaft.app.family.repository.DutyRotationRepository;
import com.mannschaft.app.family.service.DutyRotationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("DutyRotationService 単体テスト")
class DutyRotationServiceTest {

    @Mock private DutyRotationRepository dutyRotationRepository;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks private DutyRotationService service;

    @Nested
    @DisplayName("createDuty")
    class CreateDuty {

        @Test
        @DisplayName("正常系: 当番ローテーションが作成される")
        void 作成_正常_保存() {
            // Given
            given(dutyRotationRepository.countByTeamIdAndDeletedAtIsNull(1L)).willReturn(0L);
            DutyRotationEntity saved = DutyRotationEntity.builder()
                    .teamId(1L).dutyName("ゴミ出し").rotationType(RotationType.DAILY)
                    .memberOrder("[1,2,3]").startDate(LocalDate.now())
                    .isEnabled(true).createdBy(100L).build();
            given(dutyRotationRepository.save(any(DutyRotationEntity.class))).willReturn(saved);

            DutyRotationRequest req = new DutyRotationRequest("ゴミ出し", "DAILY",
                    List.of(1L, 2L, 3L), LocalDate.now(), null, true);

            // When
            ApiResponse<DutyRotationResponse> result = service.createDuty(1L, 100L, req);

            // Then
            assertThat(result.getData().getDutyName()).isEqualTo("ゴミ出し");
        }

        @Test
        @DisplayName("異常系: 当番数上限超過でFAMILY_017例外")
        void 作成_上限超過_例外() {
            // Given
            given(dutyRotationRepository.countByTeamIdAndDeletedAtIsNull(1L)).willReturn(10L);

            // When / Then
            assertThatThrownBy(() -> service.createDuty(1L, 100L,
                    new DutyRotationRequest("テスト", "DAILY", List.of(1L), LocalDate.now(), null, true)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FAMILY_017"));
        }
    }

    @Nested
    @DisplayName("deleteDuty")
    class DeleteDuty {

        @Test
        @DisplayName("異常系: 当番不在でFAMILY_016例外")
        void 削除_不在_例外() {
            // Given
            given(dutyRotationRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.deleteDuty(1L, 1L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FAMILY_016"));
        }
    }
}
