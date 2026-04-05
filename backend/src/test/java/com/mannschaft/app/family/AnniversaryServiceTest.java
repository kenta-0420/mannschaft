package com.mannschaft.app.family;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.family.dto.AnniversaryRequest;
import com.mannschaft.app.family.dto.AnniversaryResponse;
import com.mannschaft.app.family.entity.TeamAnniversaryEntity;
import com.mannschaft.app.family.repository.TeamAnniversaryRepository;
import com.mannschaft.app.family.service.AnniversaryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("AnniversaryService 単体テスト")
class AnniversaryServiceTest {

    @Mock private TeamAnniversaryRepository teamAnniversaryRepository;
    @InjectMocks private AnniversaryService service;

    @Nested
    @DisplayName("createAnniversary")
    class CreateAnniversary {

        @Test
        @DisplayName("正常系: 記念日が作成される")
        void 作成_正常_保存() {
            // Given
            given(teamAnniversaryRepository.countByTeamIdAndDeletedAtIsNull(1L)).willReturn(0L);
            TeamAnniversaryEntity saved = TeamAnniversaryEntity.builder()
                    .teamId(1L).name("結婚記念日").date(LocalDate.of(2025, 6, 15))
                    .repeatAnnually(true).notifyDaysBefore(1).createdBy(100L).build();
            given(teamAnniversaryRepository.save(any(TeamAnniversaryEntity.class))).willReturn(saved);

            AnniversaryRequest req = new AnniversaryRequest("結婚記念日", LocalDate.of(2025, 6, 15), true, 1);

            // When
            ApiResponse<AnniversaryResponse> result = service.createAnniversary(1L, 100L, req);

            // Then
            assertThat(result.getData().getName()).isEqualTo("結婚記念日");
        }

        @Test
        @DisplayName("異常系: 記念日数上限超過でFAMILY_019例外")
        void 作成_上限超過_例外() {
            // Given
            given(teamAnniversaryRepository.countByTeamIdAndDeletedAtIsNull(1L)).willReturn(50L);

            // When / Then
            assertThatThrownBy(() -> service.createAnniversary(1L, 100L,
                    new AnniversaryRequest("テスト", LocalDate.now(), true, 1)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FAMILY_019"));
        }
    }

    @Nested
    @DisplayName("deleteAnniversary")
    class DeleteAnniversary {

        @Test
        @DisplayName("異常系: 記念日不在でFAMILY_018例外")
        void 削除_不在_例外() {
            // Given
            given(teamAnniversaryRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.deleteAnniversary(1L, 1L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FAMILY_018"));
        }
    }
}
