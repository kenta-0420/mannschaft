package com.mannschaft.app.resident;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.resident.dto.CreateDwellingUnitRequest;
import com.mannschaft.app.resident.dto.DwellingUnitResponse;
import com.mannschaft.app.resident.entity.DwellingUnitEntity;
import com.mannschaft.app.resident.mapper.ResidentMapper;
import com.mannschaft.app.resident.repository.DwellingUnitRepository;
import com.mannschaft.app.resident.service.DwellingUnitService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("DwellingUnitService 単体テスト")
class DwellingUnitServiceTest {

    @Mock private DwellingUnitRepository dwellingUnitRepository;
    @Mock private ResidentMapper residentMapper;
    @InjectMocks private DwellingUnitService service;

    private static final Long TEAM_ID = 1L;

    @Nested
    @DisplayName("createForTeam")
    class CreateForTeam {

        @Test
        @DisplayName("正常系: チームの居室が作成される")
        void 作成_正常_保存() {
            // Given
            CreateDwellingUnitRequest req = new CreateDwellingUnitRequest(
                    "101", (short) 1, null, null, null, null);
            given(dwellingUnitRepository.existsByTeamIdAndUnitNumber(TEAM_ID, "101")).willReturn(false);
            given(dwellingUnitRepository.save(any(DwellingUnitEntity.class))).willAnswer(inv -> inv.getArgument(0));
            given(residentMapper.toDwellingUnitResponse(any(DwellingUnitEntity.class)))
                    .willReturn(new DwellingUnitResponse(1L, "TEAM", TEAM_ID, null, "101", (short) 1, null, null, "STANDARD", null, (short) 0, null, null));

            // When
            DwellingUnitResponse result = service.createForTeam(TEAM_ID, req);

            // Then
            assertThat(result.getUnitNumber()).isEqualTo("101");
            verify(dwellingUnitRepository).save(any(DwellingUnitEntity.class));
        }

        @Test
        @DisplayName("異常系: 居室番号重複でRESIDENT_002例外")
        void 作成_重複_例外() {
            // Given
            CreateDwellingUnitRequest req = new CreateDwellingUnitRequest(
                    "101", (short) 1, null, null, null, null);
            given(dwellingUnitRepository.existsByTeamIdAndUnitNumber(TEAM_ID, "101")).willReturn(true);

            // When / Then
            assertThatThrownBy(() -> service.createForTeam(TEAM_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("RESIDENT_002"));
        }
    }

    @Nested
    @DisplayName("getByTeam")
    class GetByTeam {

        @Test
        @DisplayName("異常系: 居室不在でRESIDENT_001例外")
        void 取得_不在_例外() {
            // Given
            given(dwellingUnitRepository.findByIdAndTeamId(1L, TEAM_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.getByTeam(TEAM_ID, 1L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("RESIDENT_001"));
        }
    }

    @Nested
    @DisplayName("deleteForTeam")
    class DeleteForTeam {

        @Test
        @DisplayName("正常系: チームの居室が論理削除される")
        void 削除_正常() {
            // Given
            DwellingUnitEntity entity = DwellingUnitEntity.builder()
                    .scopeType("TEAM").teamId(TEAM_ID).unitNumber("101").build();
            given(dwellingUnitRepository.findByIdAndTeamId(1L, TEAM_ID)).willReturn(Optional.of(entity));
            given(dwellingUnitRepository.save(any(DwellingUnitEntity.class))).willReturn(entity);

            // When
            service.deleteForTeam(TEAM_ID, 1L);

            // Then
            verify(dwellingUnitRepository).save(any(DwellingUnitEntity.class));
        }
    }
}
