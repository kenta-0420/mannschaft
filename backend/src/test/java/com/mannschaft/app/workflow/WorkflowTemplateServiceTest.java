package com.mannschaft.app.workflow;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.workflow.dto.CreateWorkflowTemplateRequest;
import com.mannschaft.app.workflow.dto.WorkflowTemplateResponse;
import com.mannschaft.app.workflow.entity.WorkflowTemplateEntity;
import com.mannschaft.app.workflow.repository.WorkflowTemplateFieldRepository;
import com.mannschaft.app.workflow.repository.WorkflowTemplateRepository;
import com.mannschaft.app.workflow.repository.WorkflowTemplateStepRepository;
import com.mannschaft.app.workflow.service.WorkflowTemplateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link WorkflowTemplateService} の単体テスト。
 * テンプレートのCRUD・有効化/無効化を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowTemplateService 単体テスト")
class WorkflowTemplateServiceTest {

    @Mock
    private WorkflowTemplateRepository templateRepository;

    @Mock
    private WorkflowTemplateStepRepository stepRepository;

    @Mock
    private WorkflowTemplateFieldRepository fieldRepository;

    @Mock
    private WorkflowMapper workflowMapper;

    @InjectMocks
    private WorkflowTemplateService workflowTemplateService;

    private static final Long TEMPLATE_ID = 100L;
    private static final Long SCOPE_ID = 1L;
    private static final Long USER_ID = 10L;
    private static final String SCOPE_TYPE = "TEAM";

    private WorkflowTemplateEntity createActiveTemplate() {
        return WorkflowTemplateEntity.builder()
                .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID).name("承認フロー")
                .createdBy(USER_ID).build();
    }

    @Nested
    @DisplayName("createTemplate")
    class CreateTemplate {

        @Test
        @DisplayName("テンプレート作成_正常_レスポンス返却")
        void テンプレート作成_正常_レスポンス返却() {
            // Given
            CreateWorkflowTemplateRequest request = new CreateWorkflowTemplateRequest(
                    "承認フロー", null, null, null, false, null, List.of(), List.of());

            WorkflowTemplateEntity savedEntity = createActiveTemplate();
            WorkflowTemplateResponse response = new WorkflowTemplateResponse(TEMPLATE_ID, SCOPE_TYPE, SCOPE_ID,
                    "承認フロー", null, null, null, false, true, 0, USER_ID, null, null, null, List.of(), List.of());

            given(templateRepository.save(any(WorkflowTemplateEntity.class))).willReturn(savedEntity);
            given(workflowMapper.toTemplateDetailResponse(any(), any(), any())).willReturn(response);

            // When
            WorkflowTemplateResponse result = workflowTemplateService.createTemplate(
                    SCOPE_TYPE, SCOPE_ID, USER_ID, request);

            // Then
            assertThat(result.getName()).isEqualTo("承認フロー");
        }
    }

    @Nested
    @DisplayName("deleteTemplate")
    class DeleteTemplate {

        @Test
        @DisplayName("テンプレート削除_正常_論理削除実行")
        void テンプレート削除_正常_論理削除実行() {
            // Given
            WorkflowTemplateEntity entity = createActiveTemplate();
            given(templateRepository.findByIdAndScopeTypeAndScopeId(TEMPLATE_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));

            // When
            workflowTemplateService.deleteTemplate(SCOPE_TYPE, SCOPE_ID, TEMPLATE_ID);

            // Then
            assertThat(entity.getDeletedAt()).isNotNull();
            verify(templateRepository).save(entity);
        }

        @Test
        @DisplayName("テンプレート削除_存在しない_BusinessException")
        void テンプレート削除_存在しない_BusinessException() {
            // Given
            given(templateRepository.findByIdAndScopeTypeAndScopeId(TEMPLATE_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> workflowTemplateService.deleteTemplate(SCOPE_TYPE, SCOPE_ID, TEMPLATE_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(WorkflowErrorCode.TEMPLATE_NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("activateTemplate")
    class ActivateTemplate {

        @Test
        @DisplayName("テンプレート有効化_正常_isActive=true")
        void テンプレート有効化_正常_isActive_true() {
            // Given
            WorkflowTemplateEntity entity = createActiveTemplate();
            entity.deactivate();
            WorkflowTemplateResponse response = new WorkflowTemplateResponse(TEMPLATE_ID, SCOPE_TYPE, SCOPE_ID,
                    "承認フロー", null, null, null, false, true, 0, USER_ID, null, null, null, List.of(), List.of());

            given(templateRepository.findByIdAndScopeTypeAndScopeId(TEMPLATE_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(templateRepository.save(entity)).willReturn(entity);
            given(stepRepository.findByTemplateIdOrderByStepOrderAsc(TEMPLATE_ID)).willReturn(List.of());
            given(fieldRepository.findByTemplateIdOrderBySortOrderAsc(TEMPLATE_ID)).willReturn(List.of());
            given(workflowMapper.toTemplateDetailResponse(entity, List.of(), List.of())).willReturn(response);

            // When
            workflowTemplateService.activateTemplate(SCOPE_TYPE, SCOPE_ID, TEMPLATE_ID);

            // Then
            assertThat(entity.getIsActive()).isTrue();
        }
    }

    @Nested
    @DisplayName("deactivateTemplate")
    class DeactivateTemplate {

        @Test
        @DisplayName("テンプレート無効化_正常_isActive=false")
        void テンプレート無効化_正常_isActive_false() {
            // Given
            WorkflowTemplateEntity entity = createActiveTemplate();
            WorkflowTemplateResponse response = new WorkflowTemplateResponse(TEMPLATE_ID, SCOPE_TYPE, SCOPE_ID,
                    "承認フロー", null, null, null, false, false, 0, USER_ID, null, null, null, List.of(), List.of());

            given(templateRepository.findByIdAndScopeTypeAndScopeId(TEMPLATE_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(templateRepository.save(entity)).willReturn(entity);
            given(stepRepository.findByTemplateIdOrderByStepOrderAsc(TEMPLATE_ID)).willReturn(List.of());
            given(fieldRepository.findByTemplateIdOrderBySortOrderAsc(TEMPLATE_ID)).willReturn(List.of());
            given(workflowMapper.toTemplateDetailResponse(entity, List.of(), List.of())).willReturn(response);

            // When
            workflowTemplateService.deactivateTemplate(SCOPE_TYPE, SCOPE_ID, TEMPLATE_ID);

            // Then
            assertThat(entity.getIsActive()).isFalse();
        }
    }
}
