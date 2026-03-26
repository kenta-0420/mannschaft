package com.mannschaft.app.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.service.dto.CreateFieldRequest;
import com.mannschaft.app.service.dto.FieldResponse;
import com.mannschaft.app.service.entity.ServiceRecordFieldEntity;
import com.mannschaft.app.service.entity.ServiceRecordSettingsEntity;
import com.mannschaft.app.service.repository.ServiceRecordFieldRepository;
import com.mannschaft.app.service.repository.ServiceRecordSettingsRepository;
import com.mannschaft.app.service.service.ServiceRecordFieldService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
@DisplayName("ServiceRecordFieldService 単体テスト")
class ServiceRecordFieldServiceTest {

    @Mock private ServiceRecordFieldRepository fieldRepository;
    @Mock private ServiceRecordSettingsRepository settingsRepository;
    @Mock private ServiceRecordMapper mapper;
    @Mock private ObjectMapper objectMapper;

    @InjectMocks
    private ServiceRecordFieldService service;

    private static final Long TEAM_ID = 1L;
    private static final Long FIELD_ID = 10L;

    @Nested
    @DisplayName("createField")
    class CreateField {
        @Test
        @DisplayName("異常系: フィールド上限超過でSERVICE_RECORD_007例外")
        void 作成_上限超過_例外() {
            given(fieldRepository.countByTeamIdAndIsActiveTrue(TEAM_ID)).willReturn(20L);
            CreateFieldRequest request = new CreateFieldRequest();
            request.setFieldName("新フィールド");
            request.setFieldType("TEXT");

            assertThatThrownBy(() -> service.createField(TEAM_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("SERVICE_RECORD_007"));
        }

        @Test
        @DisplayName("正常系: フィールドが作成される")
        void 作成_正常_保存() {
            given(fieldRepository.countByTeamIdAndIsActiveTrue(TEAM_ID)).willReturn(0L);
            CreateFieldRequest request = new CreateFieldRequest();
            request.setFieldName("テスト");
            request.setFieldType("TEXT");
            ServiceRecordFieldEntity saved = ServiceRecordFieldEntity.builder()
                    .teamId(TEAM_ID).fieldName("テスト").fieldType(FieldType.TEXT).build();
            given(fieldRepository.save(any())).willReturn(saved);
            given(mapper.toFieldResponse(saved)).willReturn(FieldResponse.builder().build());

            FieldResponse result = service.createField(TEAM_ID, request);
            assertThat(result).isNotNull();
        }
    }

    @Nested
    @DisplayName("deactivateField")
    class DeactivateField {
        @Test
        @DisplayName("異常系: フィールド不在でSERVICE_RECORD_002例外")
        void 無効化_不在_例外() {
            given(fieldRepository.findByIdAndTeamId(FIELD_ID, TEAM_ID)).willReturn(Optional.empty());
            assertThatThrownBy(() -> service.deactivateField(TEAM_ID, FIELD_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("SERVICE_RECORD_002"));
        }
    }
}
