package com.mannschaft.app.directmail;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.directmail.dto.CreateDirectMailTemplateRequest;
import com.mannschaft.app.directmail.dto.DirectMailTemplateResponse;
import com.mannschaft.app.directmail.entity.DirectMailTemplateEntity;
import com.mannschaft.app.directmail.repository.DirectMailTemplateRepository;
import com.mannschaft.app.directmail.service.DirectMailTemplateService;
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
@DisplayName("DirectMailTemplateService 単体テスト")
class DirectMailTemplateServiceTest {

    @Mock private DirectMailTemplateRepository templateRepository;
    @Mock private DirectMailMapper directMailMapper;
    @InjectMocks private DirectMailTemplateService service;

    private static final String SCOPE_TYPE = "TEAM";
    private static final Long SCOPE_ID = 1L;

    @Nested
    @DisplayName("createTemplate")
    class CreateTemplate {

        @Test
        @DisplayName("正常系: テンプレートが作成される")
        void 作成_正常_保存() {
            // Given
            CreateDirectMailTemplateRequest req = new CreateDirectMailTemplateRequest(
                    "テスト", "件名", "# 本文");
            given(templateRepository.save(any(DirectMailTemplateEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(directMailMapper.toTemplateResponse(any(DirectMailTemplateEntity.class)))
                    .willReturn(new DirectMailTemplateResponse(1L, SCOPE_TYPE, SCOPE_ID, "テスト", "件名", "# 本文", 100L, null, null));

            // When
            DirectMailTemplateResponse result = service.createTemplate(SCOPE_TYPE, SCOPE_ID, 100L, req);

            // Then
            assertThat(result.getName()).isEqualTo("テスト");
            verify(templateRepository).save(any(DirectMailTemplateEntity.class));
        }
    }

    @Nested
    @DisplayName("deleteTemplate")
    class DeleteTemplate {

        @Test
        @DisplayName("異常系: テンプレート不在でDM_002例外")
        void 削除_不在_例外() {
            // Given
            given(templateRepository.findByIdAndScopeTypeAndScopeId(1L, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.deleteTemplate(SCOPE_TYPE, SCOPE_ID, 1L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("DM_002"));
        }
    }
}
