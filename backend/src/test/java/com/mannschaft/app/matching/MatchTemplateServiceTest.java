package com.mannschaft.app.matching;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.matching.dto.CreateTemplateRequest;
import com.mannschaft.app.matching.dto.TemplateCreateResponse;
import com.mannschaft.app.matching.entity.MatchRequestTemplateEntity;
import com.mannschaft.app.matching.mapper.MatchingMapper;
import com.mannschaft.app.matching.repository.MatchRequestTemplateRepository;
import com.mannschaft.app.matching.service.MatchTemplateService;
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
import static org.mockito.Mockito.never;

/**
 * {@link MatchTemplateService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MatchTemplateService 単体テスト")
class MatchTemplateServiceTest {

    @Mock
    private MatchRequestTemplateRepository templateRepository;
    @Mock
    private MatchingMapper matchingMapper;

    @InjectMocks
    private MatchTemplateService service;

    private static final Long TEAM_ID = 1L;
    private static final Long TEMPLATE_ID = 10L;

    @Nested
    @DisplayName("createTemplate")
    class CreateTemplate {

        @Test
        @DisplayName("正常系: テンプレートが正常に作成される")
        void テンプレート正常作成() {
            // Given
            given(templateRepository.countByTeamId(TEAM_ID)).willReturn(0L);
            MatchRequestTemplateEntity saved = MatchRequestTemplateEntity.builder()
                    .teamId(TEAM_ID).name("テスト").build();
            given(templateRepository.save(any())).willReturn(saved);

            CreateTemplateRequest request = new CreateTemplateRequest("テスト", "{}");

            // When
            TemplateCreateResponse result = service.createTemplate(TEAM_ID, request);

            // Then
            assertThat(result.getName()).isEqualTo("テスト");
        }

        @Test
        @DisplayName("異常系: テンプレート数上限超過")
        void テンプレート上限超過() {
            // Given
            given(templateRepository.countByTeamId(TEAM_ID)).willReturn(20L);
            CreateTemplateRequest request = new CreateTemplateRequest("テスト", "{}");

            // When & Then
            assertThatThrownBy(() -> service.createTemplate(TEAM_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MatchingErrorCode.TEMPLATE_LIMIT_EXCEEDED);
        }
    }

    @Nested
    @DisplayName("updateTemplate")
    class UpdateTemplate {

        @Test
        @DisplayName("異常系: 他チームのテンプレート更新は不在と同じエラー")
        void 他チーム更新は不在と同じエラー() {
            // Given
            MatchRequestTemplateEntity entity = MatchRequestTemplateEntity.builder()
                    .teamId(999L).name("テスト").build();
            given(templateRepository.findById(TEMPLATE_ID)).willReturn(Optional.of(entity));

            CreateTemplateRequest request = new CreateTemplateRequest("更新", "{}");

            // When & Then
            assertThatThrownBy(() -> service.updateTemplate(TEAM_ID, TEMPLATE_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MatchingErrorCode.TEMPLATE_NOT_FOUND);
            assertThat(entity.getName()).isEqualTo("テスト");
            assertThat(entity.getTemplateJson()).isNull();
            verify(templateRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("deleteTemplate")
    class DeleteTemplate {

        @Test
        @DisplayName("異常系: 他チームのテンプレート削除は不在と同じエラーかつ削除しない")
        void 他チーム削除は不在と同じエラーで変更しない() {
            MatchRequestTemplateEntity entity = MatchRequestTemplateEntity.builder()
                    .teamId(999L).name("テスト").templateJson("{}").build();
            given(templateRepository.findById(TEMPLATE_ID)).willReturn(Optional.of(entity));

            assertThatThrownBy(() -> service.deleteTemplate(TEAM_ID, TEMPLATE_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MatchingErrorCode.TEMPLATE_NOT_FOUND);
            verify(templateRepository, never()).delete(any());
            assertThat(entity.getName()).isEqualTo("テスト");
            assertThat(entity.getTemplateJson()).isEqualTo("{}");
        }

        @Test
        @DisplayName("正常系: テンプレート削除成功")
        void テンプレート削除成功() {
            // Given
            MatchRequestTemplateEntity entity = MatchRequestTemplateEntity.builder()
                    .teamId(TEAM_ID).name("テスト").build();
            given(templateRepository.findById(TEMPLATE_ID)).willReturn(Optional.of(entity));

            // When
            service.deleteTemplate(TEAM_ID, TEMPLATE_ID);

            // Then
            verify(templateRepository).delete(entity);
        }

        @Test
        @DisplayName("異常系: テンプレートが見つからない")
        void テンプレート不存在() {
            // Given
            given(templateRepository.findById(TEMPLATE_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> service.deleteTemplate(TEAM_ID, TEMPLATE_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MatchingErrorCode.TEMPLATE_NOT_FOUND);
        }
    }
}
