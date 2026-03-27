package com.mannschaft.app.workflow;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.workflow.dto.CreateWorkflowTemplateRequest;
import com.mannschaft.app.workflow.dto.TemplateFieldRequest;
import com.mannschaft.app.workflow.dto.TemplateStepRequest;
import com.mannschaft.app.workflow.dto.UpdateWorkflowTemplateRequest;
import com.mannschaft.app.workflow.dto.WorkflowTemplateResponse;
import com.mannschaft.app.workflow.entity.WorkflowTemplateEntity;
import com.mannschaft.app.workflow.entity.WorkflowTemplateFieldEntity;
import com.mannschaft.app.workflow.entity.WorkflowTemplateStepEntity;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

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

    @Nested
    @DisplayName("listTemplates")
    class ListTemplates {

        @Test
        @DisplayName("テンプレート一覧取得_ページング_レスポンス返却")
        void テンプレート一覧取得_ページング_レスポンス返却() {
            // Given
            WorkflowTemplateEntity entity = createActiveTemplate();
            WorkflowTemplateResponse response = new WorkflowTemplateResponse(TEMPLATE_ID, SCOPE_TYPE, SCOPE_ID,
                    "承認フロー", null, null, null, false, true, 0, USER_ID, null, null, null, List.of(), List.of());
            PageRequest pageable = PageRequest.of(0, 10);
            Page<WorkflowTemplateEntity> page = new PageImpl<>(List.of(entity));

            given(templateRepository.findByScopeTypeAndScopeIdOrderBySortOrderAsc(SCOPE_TYPE, SCOPE_ID, pageable))
                    .willReturn(page);
            given(stepRepository.findByTemplateIdOrderByStepOrderAsc(any())).willReturn(List.of());
            given(fieldRepository.findByTemplateIdOrderBySortOrderAsc(any())).willReturn(List.of());
            given(workflowMapper.toTemplateDetailResponse(any(), any(), any())).willReturn(response);

            // When
            Page<WorkflowTemplateResponse> result = workflowTemplateService.listTemplates(SCOPE_TYPE, SCOPE_ID, pageable);

            // Then
            assertThat(result.getTotalElements()).isEqualTo(1);
            assertThat(result.getContent().get(0).getName()).isEqualTo("承認フロー");
        }
    }

    @Nested
    @DisplayName("listActiveTemplates")
    class ListActiveTemplates {

        @Test
        @DisplayName("有効テンプレート一覧取得_正常_レスポンス返却")
        void 有効テンプレート一覧取得_正常_レスポンス返却() {
            // Given
            WorkflowTemplateEntity entity = createActiveTemplate();
            WorkflowTemplateResponse response = new WorkflowTemplateResponse(TEMPLATE_ID, SCOPE_TYPE, SCOPE_ID,
                    "承認フロー", null, null, null, false, true, 0, USER_ID, null, null, null, List.of(), List.of());

            given(templateRepository.findByScopeTypeAndScopeIdAndIsActiveTrueOrderBySortOrderAsc(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(List.of(entity));
            given(stepRepository.findByTemplateIdOrderByStepOrderAsc(any())).willReturn(List.of());
            given(fieldRepository.findByTemplateIdOrderBySortOrderAsc(any())).willReturn(List.of());
            given(workflowMapper.toTemplateDetailResponse(any(), any(), any())).willReturn(response);

            // When
            List<WorkflowTemplateResponse> result = workflowTemplateService.listActiveTemplates(SCOPE_TYPE, SCOPE_ID);

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getIsActive()).isTrue();
        }

        @Test
        @DisplayName("有効テンプレート一覧取得_空_空リスト返却")
        void 有効テンプレート一覧取得_空_空リスト返却() {
            // Given
            given(templateRepository.findByScopeTypeAndScopeIdAndIsActiveTrueOrderBySortOrderAsc(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(List.of());

            // When
            List<WorkflowTemplateResponse> result = workflowTemplateService.listActiveTemplates(SCOPE_TYPE, SCOPE_ID);

            // Then
            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("getTemplate")
    class GetTemplate {

        @Test
        @DisplayName("テンプレート詳細取得_正常_レスポンス返却")
        void テンプレート詳細取得_正常_レスポンス返却() {
            // Given
            WorkflowTemplateEntity entity = createActiveTemplate();
            WorkflowTemplateResponse response = new WorkflowTemplateResponse(TEMPLATE_ID, SCOPE_TYPE, SCOPE_ID,
                    "承認フロー", null, null, null, false, true, 0, USER_ID, null, null, null, List.of(), List.of());

            given(templateRepository.findByIdAndScopeTypeAndScopeId(TEMPLATE_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(stepRepository.findByTemplateIdOrderByStepOrderAsc(TEMPLATE_ID)).willReturn(List.of());
            given(fieldRepository.findByTemplateIdOrderBySortOrderAsc(TEMPLATE_ID)).willReturn(List.of());
            given(workflowMapper.toTemplateDetailResponse(entity, List.of(), List.of())).willReturn(response);

            // When
            WorkflowTemplateResponse result = workflowTemplateService.getTemplate(SCOPE_TYPE, SCOPE_ID, TEMPLATE_ID);

            // Then
            assertThat(result.getName()).isEqualTo("承認フロー");
        }

        @Test
        @DisplayName("テンプレート詳細取得_存在しない_BusinessException")
        void テンプレート詳細取得_存在しない_BusinessException() {
            // Given
            given(templateRepository.findByIdAndScopeTypeAndScopeId(TEMPLATE_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> workflowTemplateService.getTemplate(SCOPE_TYPE, SCOPE_ID, TEMPLATE_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(WorkflowErrorCode.TEMPLATE_NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("updateTemplate")
    class UpdateTemplate {

        @Test
        @DisplayName("テンプレート更新_ステップとフィールドあり_正常更新")
        void テンプレート更新_ステップとフィールドあり_正常更新() {
            // Given
            WorkflowTemplateEntity entity = createActiveTemplate();
            TemplateStepRequest stepReq = new TemplateStepRequest(1, "部長承認", "ALL", "USER", null, null, null);
            TemplateFieldRequest fieldReq = new TemplateFieldRequest("reason", "理由", "TEXT", true, 0, null);
            UpdateWorkflowTemplateRequest request = new UpdateWorkflowTemplateRequest(
                    "更新後フロー", "説明", null, null, false, 1, 1L,
                    List.of(stepReq), List.of(fieldReq));

            WorkflowTemplateStepEntity savedStep = WorkflowTemplateStepEntity.builder()
                    .templateId(TEMPLATE_ID).stepOrder(1).name("部長承認")
                    .approvalType(ApprovalType.ALL).approverType(ApproverType.USER).build();
            WorkflowTemplateFieldEntity savedField = WorkflowTemplateFieldEntity.builder()
                    .templateId(TEMPLATE_ID).fieldKey("reason").fieldLabel("理由")
                    .fieldType(WorkflowFieldType.TEXT).isRequired(true).sortOrder(0).build();

            WorkflowTemplateResponse response = new WorkflowTemplateResponse(TEMPLATE_ID, SCOPE_TYPE, SCOPE_ID,
                    "更新後フロー", "説明", null, null, false, true, 1, USER_ID, null, null, null,
                    List.of(), List.of());

            given(templateRepository.findByIdAndScopeTypeAndScopeId(TEMPLATE_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(templateRepository.save(entity)).willReturn(entity);
            given(stepRepository.saveAll(any())).willReturn(List.of(savedStep));
            given(fieldRepository.saveAll(any())).willReturn(List.of(savedField));
            given(workflowMapper.toTemplateDetailResponse(any(), any(), any())).willReturn(response);

            // When
            WorkflowTemplateResponse result = workflowTemplateService.updateTemplate(
                    SCOPE_TYPE, SCOPE_ID, TEMPLATE_ID, request);

            // Then
            assertThat(result.getName()).isEqualTo("更新後フロー");
            verify(stepRepository).deleteByTemplateId(TEMPLATE_ID);
            verify(fieldRepository).deleteByTemplateId(TEMPLATE_ID);
        }

        @Test
        @DisplayName("テンプレート更新_ステップとフィールドnull_空で更新")
        void テンプレート更新_ステップとフィールドnull_空で更新() {
            // Given
            WorkflowTemplateEntity entity = createActiveTemplate();
            UpdateWorkflowTemplateRequest request = new UpdateWorkflowTemplateRequest(
                    "シンプルフロー", null, null, null, false, null, 1L, null, null);

            WorkflowTemplateResponse response = new WorkflowTemplateResponse(TEMPLATE_ID, SCOPE_TYPE, SCOPE_ID,
                    "シンプルフロー", null, null, null, false, true, 0, USER_ID, null, null, null, List.of(), List.of());

            given(templateRepository.findByIdAndScopeTypeAndScopeId(TEMPLATE_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(templateRepository.save(entity)).willReturn(entity);
            given(workflowMapper.toTemplateDetailResponse(any(), any(), any())).willReturn(response);

            // When
            WorkflowTemplateResponse result = workflowTemplateService.updateTemplate(
                    SCOPE_TYPE, SCOPE_ID, TEMPLATE_ID, request);

            // Then
            assertThat(result.getName()).isEqualTo("シンプルフロー");
        }
    }

    @Nested
    @DisplayName("getTemplateEntity")
    class GetTemplateEntity {

        @Test
        @DisplayName("テンプレートエンティティ取得_正常_エンティティ返却")
        void テンプレートエンティティ取得_正常_エンティティ返却() {
            // Given
            WorkflowTemplateEntity entity = createActiveTemplate();
            given(templateRepository.findById(TEMPLATE_ID)).willReturn(Optional.of(entity));

            // When
            WorkflowTemplateEntity result = workflowTemplateService.getTemplateEntity(TEMPLATE_ID);

            // Then
            assertThat(result.getName()).isEqualTo("承認フロー");
        }

        @Test
        @DisplayName("テンプレートエンティティ取得_存在しない_BusinessException")
        void テンプレートエンティティ取得_存在しない_BusinessException() {
            // Given
            given(templateRepository.findById(TEMPLATE_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> workflowTemplateService.getTemplateEntity(TEMPLATE_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(WorkflowErrorCode.TEMPLATE_NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("createTemplate with steps and fields")
    class CreateTemplateWithStepsAndFields {

        @Test
        @DisplayName("テンプレート作成_ステップとフィールドあり_正常作成")
        void テンプレート作成_ステップとフィールドあり_正常作成() {
            // Given
            TemplateStepRequest stepReq = new TemplateStepRequest(1, "部長承認", "ALL", "USER", "[10]", null, null);
            TemplateFieldRequest fieldReq = new TemplateFieldRequest("reason", "理由", "TEXTAREA", true, 0, null);
            CreateWorkflowTemplateRequest request = new CreateWorkflowTemplateRequest(
                    "承認フロー", null, null, null, false, 1, List.of(stepReq), List.of(fieldReq));

            WorkflowTemplateEntity savedEntity = createActiveTemplate();
            WorkflowTemplateStepEntity savedStep = WorkflowTemplateStepEntity.builder()
                    .templateId(TEMPLATE_ID).stepOrder(1).name("部長承認")
                    .approvalType(ApprovalType.ALL).approverType(ApproverType.USER).build();
            WorkflowTemplateFieldEntity savedField = WorkflowTemplateFieldEntity.builder()
                    .templateId(TEMPLATE_ID).fieldKey("reason").fieldLabel("理由")
                    .fieldType(WorkflowFieldType.TEXTAREA).isRequired(true).sortOrder(0).build();
            WorkflowTemplateResponse response = new WorkflowTemplateResponse(TEMPLATE_ID, SCOPE_TYPE, SCOPE_ID,
                    "承認フロー", null, null, null, false, true, 1, USER_ID, null, null, null, List.of(), List.of());

            given(templateRepository.save(any(WorkflowTemplateEntity.class))).willReturn(savedEntity);
            given(stepRepository.saveAll(any())).willReturn(List.of(savedStep));
            given(fieldRepository.saveAll(any())).willReturn(List.of(savedField));
            given(workflowMapper.toTemplateDetailResponse(any(), any(), any())).willReturn(response);

            // When
            WorkflowTemplateResponse result = workflowTemplateService.createTemplate(
                    SCOPE_TYPE, SCOPE_ID, USER_ID, request);

            // Then
            assertThat(result).isNotNull();
            verify(stepRepository).saveAll(any());
            verify(fieldRepository).saveAll(any());
        }
    }
}
