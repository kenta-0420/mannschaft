package com.mannschaft.app.workflow;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.workflow.dto.CreateWorkflowRequestRequest;
import com.mannschaft.app.workflow.dto.UpdateWorkflowRequestRequest;
import com.mannschaft.app.workflow.dto.WorkflowRequestResponse;
import com.mannschaft.app.workflow.entity.WorkflowRequestApproverEntity;
import com.mannschaft.app.workflow.entity.WorkflowRequestEntity;
import com.mannschaft.app.workflow.entity.WorkflowRequestStepEntity;
import com.mannschaft.app.workflow.entity.WorkflowTemplateEntity;
import com.mannschaft.app.workflow.entity.WorkflowTemplateStepEntity;
import com.mannschaft.app.workflow.repository.WorkflowRequestApproverRepository;
import com.mannschaft.app.workflow.repository.WorkflowRequestRepository;
import com.mannschaft.app.workflow.repository.WorkflowRequestStepRepository;
import com.mannschaft.app.workflow.repository.WorkflowTemplateStepRepository;
import com.mannschaft.app.workflow.service.WorkflowRequestService;
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
 * {@link WorkflowRequestService} の単体テスト。
 * 申請のCRUD・提出・取り下げ・ステータス管理を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowRequestService 単体テスト")
class WorkflowRequestServiceTest {

    @Mock
    private WorkflowRequestRepository requestRepository;

    @Mock
    private WorkflowRequestStepRepository requestStepRepository;

    @Mock
    private WorkflowRequestApproverRepository approverRepository;

    @Mock
    private WorkflowTemplateStepRepository templateStepRepository;

    @Mock
    private WorkflowTemplateService templateService;

    @Mock
    private WorkflowMapper workflowMapper;

    @InjectMocks
    private WorkflowRequestService workflowRequestService;

    private static final Long REQUEST_ID = 200L;
    private static final Long TEMPLATE_ID = 100L;
    private static final Long SCOPE_ID = 1L;
    private static final Long USER_ID = 10L;
    private static final String SCOPE_TYPE = "TEAM";

    private WorkflowTemplateEntity createActiveTemplate() {
        return WorkflowTemplateEntity.builder()
                .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID).name("承認フロー")
                .createdBy(1L).build();
    }

    private WorkflowRequestEntity createDraftRequest() {
        return WorkflowRequestEntity.builder()
                .templateId(TEMPLATE_ID).scopeType(SCOPE_TYPE).scopeId(SCOPE_ID)
                .title("休暇申請").requestedBy(USER_ID).build();
    }

    @Nested
    @DisplayName("createRequest")
    class CreateRequest {

        @Test
        @DisplayName("申請作成_正常_レスポンス返却")
        void 申請作成_正常_レスポンス返却() {
            // Given
            CreateWorkflowRequestRequest request = new CreateWorkflowRequestRequest(TEMPLATE_ID, "休暇申請", null, null, null);

            WorkflowTemplateEntity template = createActiveTemplate();
            WorkflowRequestEntity savedEntity = createDraftRequest();
            WorkflowRequestResponse response = new WorkflowRequestResponse(REQUEST_ID, TEMPLATE_ID,
                    SCOPE_TYPE, SCOPE_ID, "休暇申請", "DRAFT", USER_ID, null, null,
                    null, null, null, null, null, null, List.of());

            given(templateService.getTemplateEntity(TEMPLATE_ID)).willReturn(template);
            given(requestRepository.save(any(WorkflowRequestEntity.class))).willReturn(savedEntity);
            given(requestStepRepository.findByRequestIdOrderByStepOrderAsc(any())).willReturn(List.of());
            given(workflowMapper.toRequestDetailResponse(any(), any())).willReturn(response);

            // When
            WorkflowRequestResponse result = workflowRequestService.createRequest(
                    SCOPE_TYPE, SCOPE_ID, USER_ID, request);

            // Then
            assertThat(result.getTitle()).isEqualTo("休暇申請");
        }

        @Test
        @DisplayName("申請作成_テンプレート無効_BusinessException")
        void 申請作成_テンプレート無効_BusinessException() {
            // Given
            CreateWorkflowRequestRequest request = new CreateWorkflowRequestRequest(TEMPLATE_ID, "休暇申請", null, null, null);

            WorkflowTemplateEntity template = createActiveTemplate();
            template.deactivate();

            given(templateService.getTemplateEntity(TEMPLATE_ID)).willReturn(template);

            // When & Then
            assertThatThrownBy(() -> workflowRequestService.createRequest(SCOPE_TYPE, SCOPE_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(WorkflowErrorCode.TEMPLATE_INACTIVE));
        }
    }

    @Nested
    @DisplayName("updateRequest")
    class UpdateRequest {

        @Test
        @DisplayName("申請更新_DRAFT状態_正常")
        void 申請更新_DRAFT状態_正常() {
            // Given
            UpdateWorkflowRequestRequest request = new UpdateWorkflowRequestRequest("更新タイトル", null, null);

            WorkflowRequestEntity entity = createDraftRequest();
            WorkflowRequestResponse response = new WorkflowRequestResponse(REQUEST_ID, TEMPLATE_ID,
                    SCOPE_TYPE, SCOPE_ID, "更新タイトル", "DRAFT", USER_ID, null, null,
                    null, null, null, null, null, null, List.of());

            given(requestRepository.findByIdAndScopeTypeAndScopeId(REQUEST_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(requestRepository.save(entity)).willReturn(entity);
            given(requestStepRepository.findByRequestIdOrderByStepOrderAsc(any())).willReturn(List.of());
            given(workflowMapper.toRequestDetailResponse(any(), any())).willReturn(response);

            // When
            WorkflowRequestResponse result = workflowRequestService.updateRequest(
                    SCOPE_TYPE, SCOPE_ID, REQUEST_ID, request);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("申請更新_IN_PROGRESS状態_BusinessException")
        void 申請更新_IN_PROGRESS状態_BusinessException() {
            // Given
            UpdateWorkflowRequestRequest request = new UpdateWorkflowRequestRequest("更新", null, null);

            WorkflowRequestEntity entity = createDraftRequest();
            entity.submit();
            entity.startProgress();

            given(requestRepository.findByIdAndScopeTypeAndScopeId(REQUEST_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));

            // When & Then
            assertThatThrownBy(() -> workflowRequestService.updateRequest(SCOPE_TYPE, SCOPE_ID, REQUEST_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(WorkflowErrorCode.INVALID_STATUS_TRANSITION));
        }
    }

    @Nested
    @DisplayName("withdrawRequest")
    class WithdrawRequest {

        @Test
        @DisplayName("申請取り下げ_IN_PROGRESS状態_正常")
        void 申請取り下げ_IN_PROGRESS状態_正常() {
            // Given
            WorkflowRequestEntity entity = createDraftRequest();
            entity.submit();
            entity.startProgress();

            WorkflowRequestResponse response = new WorkflowRequestResponse(REQUEST_ID, TEMPLATE_ID,
                    SCOPE_TYPE, SCOPE_ID, "休暇申請", "WITHDRAWN", USER_ID, null, null,
                    null, null, null, null, null, null, List.of());

            given(requestRepository.findByIdAndScopeTypeAndScopeId(REQUEST_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(requestRepository.save(entity)).willReturn(entity);
            given(requestStepRepository.findByRequestIdOrderByStepOrderAsc(any())).willReturn(List.of());
            given(workflowMapper.toRequestDetailResponse(any(), any())).willReturn(response);

            // When
            workflowRequestService.withdrawRequest(SCOPE_TYPE, SCOPE_ID, REQUEST_ID);

            // Then
            assertThat(entity.getStatus()).isEqualTo(WorkflowStatus.WITHDRAWN);
        }

        @Test
        @DisplayName("申請取り下げ_APPROVED状態_BusinessException")
        void 申請取り下げ_APPROVED状態_BusinessException() {
            // Given
            WorkflowRequestEntity entity = createDraftRequest();
            entity.submit();
            entity.startProgress();
            entity.approve();

            given(requestRepository.findByIdAndScopeTypeAndScopeId(REQUEST_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));

            // When & Then
            assertThatThrownBy(() -> workflowRequestService.withdrawRequest(SCOPE_TYPE, SCOPE_ID, REQUEST_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(WorkflowErrorCode.INVALID_STATUS_TRANSITION));
        }
    }

    @Nested
    @DisplayName("deleteRequest")
    class DeleteRequest {

        @Test
        @DisplayName("申請削除_正常_論理削除実行")
        void 申請削除_正常_論理削除実行() {
            // Given
            WorkflowRequestEntity entity = createDraftRequest();
            given(requestRepository.findByIdAndScopeTypeAndScopeId(REQUEST_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));

            // When
            workflowRequestService.deleteRequest(SCOPE_TYPE, SCOPE_ID, REQUEST_ID);

            // Then
            assertThat(entity.getDeletedAt()).isNotNull();
            verify(requestRepository).save(entity);
        }
    }

    @Nested
    @DisplayName("listRequests")
    class ListRequests {

        @Test
        @DisplayName("申請一覧取得_ステータスあり_フィルタされる")
        void 申請一覧取得_ステータスあり_フィルタされる() {
            // Given
            WorkflowRequestEntity entity = createDraftRequest();
            PageRequest pageable = PageRequest.of(0, 10);
            Page<WorkflowRequestEntity> page = new PageImpl<>(List.of(entity));
            WorkflowRequestResponse response = new WorkflowRequestResponse(REQUEST_ID, TEMPLATE_ID,
                    SCOPE_TYPE, SCOPE_ID, "休暇申請", "DRAFT", USER_ID, null, null,
                    null, null, null, null, null, null, List.of());

            given(requestRepository.findByScopeTypeAndScopeIdAndStatusOrderByCreatedAtDesc(
                    SCOPE_TYPE, SCOPE_ID, WorkflowStatus.DRAFT, pageable)).willReturn(page);
            given(requestStepRepository.findByRequestIdOrderByStepOrderAsc(any())).willReturn(List.of());
            given(workflowMapper.toRequestDetailResponse(any(), any())).willReturn(response);

            // When
            Page<WorkflowRequestResponse> result = workflowRequestService.listRequests(
                    SCOPE_TYPE, SCOPE_ID, "DRAFT", pageable);

            // Then
            assertThat(result.getTotalElements()).isEqualTo(1);
        }

        @Test
        @DisplayName("申請一覧取得_ステータスなし_全件取得")
        void 申請一覧取得_ステータスなし_全件取得() {
            // Given
            PageRequest pageable = PageRequest.of(0, 10);
            Page<WorkflowRequestEntity> page = new PageImpl<>(List.of());
            given(requestRepository.findByScopeTypeAndScopeIdOrderByCreatedAtDesc(SCOPE_TYPE, SCOPE_ID, pageable))
                    .willReturn(page);

            // When
            Page<WorkflowRequestResponse> result = workflowRequestService.listRequests(
                    SCOPE_TYPE, SCOPE_ID, null, pageable);

            // Then
            assertThat(result.getTotalElements()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("getRequest")
    class GetRequest {

        @Test
        @DisplayName("申請詳細取得_正常_レスポンス返却")
        void 申請詳細取得_正常_レスポンス返却() {
            // Given
            WorkflowRequestEntity entity = createDraftRequest();
            WorkflowRequestResponse response = new WorkflowRequestResponse(REQUEST_ID, TEMPLATE_ID,
                    SCOPE_TYPE, SCOPE_ID, "休暇申請", "DRAFT", USER_ID, null, null,
                    null, null, null, null, null, null, List.of());

            given(requestRepository.findByIdAndScopeTypeAndScopeId(REQUEST_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(requestStepRepository.findByRequestIdOrderByStepOrderAsc(any())).willReturn(List.of());
            given(workflowMapper.toRequestDetailResponse(any(), any())).willReturn(response);

            // When
            WorkflowRequestResponse result = workflowRequestService.getRequest(SCOPE_TYPE, SCOPE_ID, REQUEST_ID);

            // Then
            assertThat(result.getTitle()).isEqualTo("休暇申請");
        }

        @Test
        @DisplayName("申請詳細取得_存在しない_BusinessException")
        void 申請詳細取得_存在しない_BusinessException() {
            // Given
            given(requestRepository.findByIdAndScopeTypeAndScopeId(REQUEST_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> workflowRequestService.getRequest(SCOPE_TYPE, SCOPE_ID, REQUEST_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(WorkflowErrorCode.REQUEST_NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("submitRequest")
    class SubmitRequest {

        @Test
        @DisplayName("申請提出_ステップなし_PENDINGになる")
        void 申請提出_ステップなし_PENDINGになる() {
            // Given
            WorkflowRequestEntity entity = createDraftRequest();
            WorkflowRequestResponse response = new WorkflowRequestResponse(REQUEST_ID, TEMPLATE_ID,
                    SCOPE_TYPE, SCOPE_ID, "休暇申請", "PENDING", USER_ID, null, 1,
                    null, null, null, null, null, null, List.of());

            given(requestRepository.findByIdAndScopeTypeAndScopeId(REQUEST_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(templateStepRepository.findByTemplateIdOrderByStepOrderAsc(TEMPLATE_ID))
                    .willReturn(List.of());
            given(requestRepository.save(entity)).willReturn(entity);
            given(requestStepRepository.findByRequestIdOrderByStepOrderAsc(any())).willReturn(List.of());
            given(workflowMapper.toRequestDetailResponse(any(), any())).willReturn(response);

            // When
            WorkflowRequestResponse result = workflowRequestService.submitRequest(SCOPE_TYPE, SCOPE_ID, REQUEST_ID);

            // Then
            assertThat(result.getStatus()).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("申請提出_ステップあり_IN_PROGRESSになる")
        void 申請提出_ステップあり_IN_PROGRESSになる() {
            // Given
            WorkflowRequestEntity entity = createDraftRequest();
            WorkflowTemplateStepEntity templateStep = WorkflowTemplateStepEntity.builder()
                    .templateId(TEMPLATE_ID).stepOrder(1).name("部長承認")
                    .approvalType(ApprovalType.ALL).approverType(ApproverType.USER)
                    .approverUserIds("[10]").build();
            WorkflowRequestStepEntity savedStep = WorkflowRequestStepEntity.builder()
                    .requestId(REQUEST_ID).stepOrder(1).build();
            savedStep.startProgress();

            WorkflowRequestResponse response = new WorkflowRequestResponse(REQUEST_ID, TEMPLATE_ID,
                    SCOPE_TYPE, SCOPE_ID, "休暇申請", "IN_PROGRESS", USER_ID, null, 1,
                    null, null, null, null, null, null, List.of());

            given(requestRepository.findByIdAndScopeTypeAndScopeId(REQUEST_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(templateStepRepository.findByTemplateIdOrderByStepOrderAsc(TEMPLATE_ID))
                    .willReturn(List.of(templateStep));
            given(requestStepRepository.save(any(WorkflowRequestStepEntity.class))).willReturn(savedStep);
            given(approverRepository.save(any(WorkflowRequestApproverEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(requestStepRepository.findByRequestIdAndStepOrder(any(), any()))
                    .willReturn(Optional.of(savedStep));
            given(requestRepository.save(entity)).willReturn(entity);
            given(requestStepRepository.findByRequestIdOrderByStepOrderAsc(any())).willReturn(List.of(savedStep));
            given(approverRepository.findByRequestStepId(any())).willReturn(List.of());
            given(workflowMapper.toApproverResponseList(any())).willReturn(List.of());
            given(workflowMapper.toRequestDetailResponse(any(), any())).willReturn(response);

            // When
            WorkflowRequestResponse result = workflowRequestService.submitRequest(SCOPE_TYPE, SCOPE_ID, REQUEST_ID);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("申請提出_PENDING状態_BusinessException")
        void 申請提出_PENDING状態_BusinessException() {
            // Given
            WorkflowRequestEntity entity = createDraftRequest();
            entity.submit();

            given(requestRepository.findByIdAndScopeTypeAndScopeId(REQUEST_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));

            // When & Then
            assertThatThrownBy(() -> workflowRequestService.submitRequest(SCOPE_TYPE, SCOPE_ID, REQUEST_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(WorkflowErrorCode.INVALID_STATUS_TRANSITION));
        }
    }

    @Nested
    @DisplayName("getRequestEntity")
    class GetRequestEntity {

        @Test
        @DisplayName("申請エンティティ取得_正常_エンティティ返却")
        void 申請エンティティ取得_正常_エンティティ返却() {
            // Given
            WorkflowRequestEntity entity = createDraftRequest();
            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.of(entity));

            // When
            WorkflowRequestEntity result = workflowRequestService.getRequestEntity(REQUEST_ID);

            // Then
            assertThat(result.getTitle()).isEqualTo("休暇申請");
        }

        @Test
        @DisplayName("申請エンティティ取得_存在しない_BusinessException")
        void 申請エンティティ取得_存在しない_BusinessException() {
            // Given
            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> workflowRequestService.getRequestEntity(REQUEST_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(WorkflowErrorCode.REQUEST_NOT_FOUND));
        }
    }
}
