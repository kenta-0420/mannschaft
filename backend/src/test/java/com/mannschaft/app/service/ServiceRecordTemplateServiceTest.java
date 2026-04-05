package com.mannschaft.app.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.service.dto.CreateTemplateRequest;
import com.mannschaft.app.service.entity.ServiceRecordTemplateEntity;
import com.mannschaft.app.service.repository.ServiceRecordFieldRepository;
import com.mannschaft.app.service.repository.ServiceRecordTemplateRepository;
import com.mannschaft.app.service.repository.ServiceRecordTemplateValueRepository;
import com.mannschaft.app.service.service.ServiceRecordTemplateService;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("ServiceRecordTemplateService 単体テスト")
class ServiceRecordTemplateServiceTest {

    @Mock private ServiceRecordTemplateRepository templateRepository;
    @Mock private ServiceRecordTemplateValueRepository templateValueRepository;
    @Mock private ServiceRecordFieldRepository fieldRepository;
    @Mock private ServiceRecordMapper mapper;

    @InjectMocks
    private ServiceRecordTemplateService service;

    private static final Long TEAM_ID = 1L;
    private static final Long TEMPLATE_ID = 10L;

    @Nested
    @DisplayName("createTeamTemplate")
    class CreateTeamTemplate {
        @Test
        @DisplayName("異常系: テンプレート上限超過でSERVICE_RECORD_008例外")
        void 作成_上限超過_例外() {
            given(templateRepository.countByTeamId(TEAM_ID)).willReturn(10L);
            CreateTemplateRequest request = new CreateTemplateRequest();
            request.setName("テンプレート");

            assertThatThrownBy(() -> service.createTeamTemplate(TEAM_ID, 100L, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("SERVICE_RECORD_008"));
        }
    }

    @Nested
    @DisplayName("deleteTeamTemplate")
    class DeleteTeamTemplate {
        @Test
        @DisplayName("正常系: テンプレートが論理削除される")
        void 削除_正常_論理削除() {
            ServiceRecordTemplateEntity entity = ServiceRecordTemplateEntity.builder()
                    .teamId(TEAM_ID).name("テスト").build();
            given(templateRepository.findByIdAndTeamId(TEMPLATE_ID, TEAM_ID)).willReturn(Optional.of(entity));
            service.deleteTeamTemplate(TEAM_ID, TEMPLATE_ID);
            verify(templateRepository).save(entity);
        }

        @Test
        @DisplayName("異常系: テンプレート不在でSERVICE_RECORD_003例外")
        void 削除_不在_例外() {
            given(templateRepository.findByIdAndTeamId(TEMPLATE_ID, TEAM_ID)).willReturn(Optional.empty());
            assertThatThrownBy(() -> service.deleteTeamTemplate(TEAM_ID, TEMPLATE_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("SERVICE_RECORD_003"));
        }
    }
}
