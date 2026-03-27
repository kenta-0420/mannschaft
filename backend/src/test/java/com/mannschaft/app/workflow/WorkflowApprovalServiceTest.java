package com.mannschaft.app.workflow;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.workflow.dto.ApprovalDecisionRequest;
import com.mannschaft.app.workflow.dto.RequestStepResponse;
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
import com.mannschaft.app.workflow.service.WorkflowApprovalService;
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

/**
 * {@link WorkflowApprovalService} の単体テスト。
 * 承認・却下の判断とステップ進行ロジックを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowApprovalService 単体テスト")
class WorkflowApprovalServiceTest {

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
    private WorkflowApprovalService workflowApprovalService;

    private static final Long REQUEST_ID = 200L;
    private static final Long TEMPLATE_ID = 100L;
    private static final Long STEP_ID = 300L;
    private static final Long USER_ID = 10L;

    private WorkflowRequestEntity createInProgressRequest() {
        WorkflowRequestEntity entity = WorkflowRequestEntity.builder()
                .templateId(TEMPLATE_ID).scopeType("TEAM").scopeId(1L)
                .title("休暇申請").requestedBy(1L).build();
        entity.submit();
        entity.startProgress();
        return entity;
    }

    @Nested
    @DisplayName("decide")
    class Decide {

        @Test
        @DisplayName("承認判断_DRAFT状態_BusinessException")
        void 承認判断_DRAFT状態_BusinessException() {
            // Given
            ApprovalDecisionRequest request = new ApprovalDecisionRequest("APPROVED", null, null);

            WorkflowRequestEntity entity = WorkflowRequestEntity.builder()
                    .templateId(TEMPLATE_ID).scopeType("TEAM").scopeId(1L)
                    .title("テスト").requestedBy(1L).build();

            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.of(entity));

            // When & Then
            assertThatThrownBy(() -> workflowApprovalService.decide(REQUEST_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(WorkflowErrorCode.INVALID_STATUS_TRANSITION));
        }

        @Test
        @DisplayName("承認判断_承認者でない_BusinessException")
        void 承認判断_承認者でない_BusinessException() {
            // Given
            ApprovalDecisionRequest request = new ApprovalDecisionRequest("APPROVED", null, null);

            WorkflowRequestEntity entity = createInProgressRequest();
            WorkflowRequestStepEntity step = WorkflowRequestStepEntity.builder()
                    .requestId(REQUEST_ID).stepOrder(1).build();
            step.startProgress();

            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.of(entity));
            given(requestStepRepository.findByRequestIdAndStepOrder(REQUEST_ID, 1))
                    .willReturn(Optional.of(step));
            given(approverRepository.findByRequestStepIdAndApproverUserId(any(), any()))
                    .willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> workflowApprovalService.decide(REQUEST_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(WorkflowErrorCode.NOT_APPROVER));
        }

        @Test
        @DisplayName("承認判断_既に判断済み_BusinessException")
        void 承認判断_既に判断済み_BusinessException() {
            // Given
            ApprovalDecisionRequest request = new ApprovalDecisionRequest("APPROVED", null, null);

            WorkflowRequestEntity entity = createInProgressRequest();
            WorkflowRequestStepEntity step = WorkflowRequestStepEntity.builder()
                    .requestId(REQUEST_ID).stepOrder(1).build();
            step.startProgress();
            WorkflowRequestApproverEntity approver = WorkflowRequestApproverEntity.builder()
                    .requestStepId(STEP_ID).approverUserId(USER_ID).build();
            approver.approve("OK", null);

            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.of(entity));
            given(requestStepRepository.findByRequestIdAndStepOrder(REQUEST_ID, 1))
                    .willReturn(Optional.of(step));
            given(approverRepository.findByRequestStepIdAndApproverUserId(any(), any()))
                    .willReturn(Optional.of(approver));

            // When & Then
            assertThatThrownBy(() -> workflowApprovalService.decide(REQUEST_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(WorkflowErrorCode.ALREADY_DECIDED));
        }

        @Test
        @DisplayName("承認判断_印鑑必須で印鑑なし_BusinessException")
        void 承認判断_印鑑必須で印鑑なし_BusinessException() {
            // Given
            ApprovalDecisionRequest request = new ApprovalDecisionRequest("APPROVED", "承認します", null);

            WorkflowRequestEntity entity = createInProgressRequest();
            WorkflowRequestStepEntity step = WorkflowRequestStepEntity.builder()
                    .requestId(REQUEST_ID).stepOrder(1).build();
            step.startProgress();
            WorkflowRequestApproverEntity approver = WorkflowRequestApproverEntity.builder()
                    .requestStepId(STEP_ID).approverUserId(USER_ID).build();
            WorkflowTemplateEntity template = WorkflowTemplateEntity.builder()
                    .scopeType("TEAM").scopeId(1L).name("承認フロー")
                    .isSealRequired(true).createdBy(1L).build();

            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.of(entity));
            given(requestStepRepository.findByRequestIdAndStepOrder(REQUEST_ID, 1))
                    .willReturn(Optional.of(step));
            given(approverRepository.findByRequestStepIdAndApproverUserId(any(), any()))
                    .willReturn(Optional.of(approver));
            given(templateService.getTemplateEntity(TEMPLATE_ID)).willReturn(template);

            // When & Then
            assertThatThrownBy(() -> workflowApprovalService.decide(REQUEST_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(WorkflowErrorCode.SEAL_REQUIRED));
        }

        @Test
        @DisplayName("承認判断_存在しない申請_BusinessException")
        void 承認判断_存在しない申請_BusinessException() {
            // Given
            ApprovalDecisionRequest request = new ApprovalDecisionRequest("APPROVED", null, null);

            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> workflowApprovalService.decide(REQUEST_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(WorkflowErrorCode.REQUEST_NOT_FOUND));
        }

        @Test
        @DisplayName("承認判断_REJECTEDを選択_申請が却下される")
        void 承認判断_REJECTEDを選択_申請が却下される() {
            // Given
            ApprovalDecisionRequest request = new ApprovalDecisionRequest("REJECTED", "却下します", null);

            WorkflowRequestEntity entity = createInProgressRequest();
            WorkflowRequestStepEntity step = WorkflowRequestStepEntity.builder()
                    .requestId(REQUEST_ID).stepOrder(1).build();
            step.startProgress();
            WorkflowRequestApproverEntity approver = WorkflowRequestApproverEntity.builder()
                    .requestStepId(STEP_ID).approverUserId(USER_ID).build();
            WorkflowTemplateEntity template = WorkflowTemplateEntity.builder()
                    .scopeType("TEAM").scopeId(1L).name("承認フロー")
                    .isSealRequired(false).createdBy(1L).build();
            WorkflowTemplateStepEntity templateStep = WorkflowTemplateStepEntity.builder()
                    .templateId(TEMPLATE_ID).stepOrder(1).name("ステップ1")
                    .approvalType(ApprovalType.ALL).approverType(ApproverType.USER).build();

            WorkflowRequestResponse response = new WorkflowRequestResponse(REQUEST_ID, TEMPLATE_ID,
                    "TEAM", 1L, "休暇申請", "REJECTED", 1L, null, 1,
                    null, null, null, null, null, null, List.of());

            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.of(entity));
            given(requestStepRepository.findByRequestIdAndStepOrder(REQUEST_ID, 1))
                    .willReturn(Optional.of(step));
            given(approverRepository.findByRequestStepIdAndApproverUserId(any(), any()))
                    .willReturn(Optional.of(approver));
            given(templateService.getTemplateEntity(TEMPLATE_ID)).willReturn(template);
            given(approverRepository.save(any())).willReturn(approver);
            given(templateStepRepository.findByTemplateIdOrderByStepOrderAsc(TEMPLATE_ID))
                    .willReturn(List.of(templateStep));
            given(approverRepository.countByRequestStepId(any())).willReturn(1L);
            given(approverRepository.countByRequestStepIdAndDecision(any(), any())).willReturn(1L);
            given(requestStepRepository.save(any())).willReturn(step);
            given(requestRepository.save(entity)).willReturn(entity);
            given(requestStepRepository.findByRequestIdOrderByStepOrderAsc(any())).willReturn(List.of());
            given(workflowMapper.toApproverResponseList(any())).willReturn(List.of());
            given(workflowMapper.toRequestDetailResponse(any(), any())).willReturn(response);

            // When
            WorkflowRequestResponse result = workflowApprovalService.decide(REQUEST_ID, USER_ID, request);

            // Then
            assertThat(result.getStatus()).isEqualTo("REJECTED");
            assertThat(entity.getStatus()).isEqualTo(WorkflowStatus.REJECTED);
        }

        @Test
        @DisplayName("承認判断_APPROVEDで全員承認済み_次ステップへ進む")
        void 承認判断_APPROVEDで全員承認済み_次ステップへ進む() {
            // Given
            ApprovalDecisionRequest request = new ApprovalDecisionRequest("APPROVED", "承認します", null);

            WorkflowRequestEntity entity = createInProgressRequest();
            WorkflowRequestStepEntity currentStep = WorkflowRequestStepEntity.builder()
                    .requestId(REQUEST_ID).stepOrder(1).build();
            currentStep.startProgress();
            WorkflowRequestStepEntity nextStep = WorkflowRequestStepEntity.builder()
                    .requestId(REQUEST_ID).stepOrder(2).build();
            WorkflowRequestApproverEntity approver = WorkflowRequestApproverEntity.builder()
                    .requestStepId(STEP_ID).approverUserId(USER_ID).build();
            WorkflowTemplateEntity template = WorkflowTemplateEntity.builder()
                    .scopeType("TEAM").scopeId(1L).name("承認フロー")
                    .isSealRequired(false).createdBy(1L).build();
            WorkflowTemplateStepEntity templateStep1 = WorkflowTemplateStepEntity.builder()
                    .templateId(TEMPLATE_ID).stepOrder(1).name("ステップ1")
                    .approvalType(ApprovalType.ALL).approverType(ApproverType.USER).build();
            WorkflowTemplateStepEntity templateStep2 = WorkflowTemplateStepEntity.builder()
                    .templateId(TEMPLATE_ID).stepOrder(2).name("ステップ2")
                    .approvalType(ApprovalType.ALL).approverType(ApproverType.USER).build();

            WorkflowRequestResponse response = new WorkflowRequestResponse(REQUEST_ID, TEMPLATE_ID,
                    "TEAM", 1L, "休暇申請", "IN_PROGRESS", 1L, null, 2,
                    null, null, null, null, null, null, List.of());

            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.of(entity));
            given(requestStepRepository.findByRequestIdAndStepOrder(REQUEST_ID, 1))
                    .willReturn(Optional.of(currentStep));
            given(approverRepository.findByRequestStepIdAndApproverUserId(any(), any()))
                    .willReturn(Optional.of(approver));
            given(templateService.getTemplateEntity(TEMPLATE_ID)).willReturn(template);
            given(approverRepository.save(any())).willReturn(approver);
            given(templateStepRepository.findByTemplateIdOrderByStepOrderAsc(TEMPLATE_ID))
                    .willReturn(List.of(templateStep1, templateStep2));
            given(approverRepository.countByRequestStepId(any())).willReturn(1L);
            given(approverRepository.countByRequestStepIdAndDecision(any(), any())).willReturn(1L);
            given(requestStepRepository.save(any())).willReturn(nextStep);
            given(requestStepRepository.findByRequestIdAndStepOrder(any(), any()))
                    .willReturn(Optional.of(nextStep));
            given(requestRepository.save(entity)).willReturn(entity);
            given(requestStepRepository.findByRequestIdOrderByStepOrderAsc(any())).willReturn(List.of());
            given(workflowMapper.toApproverResponseList(any())).willReturn(List.of());
            given(workflowMapper.toRequestDetailResponse(any(), any())).willReturn(response);

            // When
            WorkflowRequestResponse result = workflowApprovalService.decide(REQUEST_ID, USER_ID, request);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("承認判断_APPROVEDで最終ステップ_申請が承認される")
        void 承認判断_APPROVEDで最終ステップ_申請が承認される() {
            // Given
            ApprovalDecisionRequest request = new ApprovalDecisionRequest("APPROVED", "承認します", null);

            WorkflowRequestEntity entity = createInProgressRequest();
            WorkflowRequestStepEntity currentStep = WorkflowRequestStepEntity.builder()
                    .requestId(REQUEST_ID).stepOrder(1).build();
            currentStep.startProgress();
            WorkflowRequestApproverEntity approver = WorkflowRequestApproverEntity.builder()
                    .requestStepId(STEP_ID).approverUserId(USER_ID).build();
            WorkflowTemplateEntity template = WorkflowTemplateEntity.builder()
                    .scopeType("TEAM").scopeId(1L).name("承認フロー")
                    .isSealRequired(false).createdBy(1L).build();
            // 単一ステップのみ
            WorkflowTemplateStepEntity templateStep = WorkflowTemplateStepEntity.builder()
                    .templateId(TEMPLATE_ID).stepOrder(1).name("最終ステップ")
                    .approvalType(ApprovalType.ALL).approverType(ApproverType.USER).build();

            WorkflowRequestResponse response = new WorkflowRequestResponse(REQUEST_ID, TEMPLATE_ID,
                    "TEAM", 1L, "休暇申請", "APPROVED", 1L, null, 1,
                    null, null, null, null, null, null, List.of());

            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.of(entity));
            given(requestStepRepository.findByRequestIdAndStepOrder(REQUEST_ID, 1))
                    .willReturn(Optional.of(currentStep));
            given(approverRepository.findByRequestStepIdAndApproverUserId(any(), any()))
                    .willReturn(Optional.of(approver));
            given(templateService.getTemplateEntity(TEMPLATE_ID)).willReturn(template);
            given(approverRepository.save(any())).willReturn(approver);
            given(templateStepRepository.findByTemplateIdOrderByStepOrderAsc(TEMPLATE_ID))
                    .willReturn(List.of(templateStep));
            given(approverRepository.countByRequestStepId(any())).willReturn(1L);
            given(approverRepository.countByRequestStepIdAndDecision(any(), any())).willReturn(1L);
            given(requestStepRepository.save(any())).willReturn(currentStep);
            given(requestRepository.save(entity)).willReturn(entity);
            given(requestStepRepository.findByRequestIdOrderByStepOrderAsc(any())).willReturn(List.of());
            given(workflowMapper.toApproverResponseList(any())).willReturn(List.of());
            given(workflowMapper.toRequestDetailResponse(any(), any())).willReturn(response);

            // When
            WorkflowRequestResponse result = workflowApprovalService.decide(REQUEST_ID, USER_ID, request);

            // Then
            assertThat(entity.getStatus()).isEqualTo(WorkflowStatus.APPROVED);
        }

        @Test
        @DisplayName("承認判断_ANYモードで1人承認_ステップ完了")
        void 承認判断_ANYモードで1人承認_ステップ完了() {
            // Given
            ApprovalDecisionRequest request = new ApprovalDecisionRequest("APPROVED", null, null);

            WorkflowRequestEntity entity = createInProgressRequest();
            WorkflowRequestStepEntity currentStep = WorkflowRequestStepEntity.builder()
                    .requestId(REQUEST_ID).stepOrder(1).build();
            currentStep.startProgress();
            WorkflowRequestApproverEntity approver = WorkflowRequestApproverEntity.builder()
                    .requestStepId(STEP_ID).approverUserId(USER_ID).build();
            WorkflowTemplateEntity template = WorkflowTemplateEntity.builder()
                    .scopeType("TEAM").scopeId(1L).name("承認フロー")
                    .isSealRequired(false).createdBy(1L).build();
            WorkflowTemplateStepEntity templateStep = WorkflowTemplateStepEntity.builder()
                    .templateId(TEMPLATE_ID).stepOrder(1).name("任意承認")
                    .approvalType(ApprovalType.ANY).approverType(ApproverType.USER).build();

            WorkflowRequestResponse response = new WorkflowRequestResponse(REQUEST_ID, TEMPLATE_ID,
                    "TEAM", 1L, "休暇申請", "APPROVED", 1L, null, 1,
                    null, null, null, null, null, null, List.of());

            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.of(entity));
            given(requestStepRepository.findByRequestIdAndStepOrder(REQUEST_ID, 1))
                    .willReturn(Optional.of(currentStep));
            given(approverRepository.findByRequestStepIdAndApproverUserId(any(), any()))
                    .willReturn(Optional.of(approver));
            given(templateService.getTemplateEntity(TEMPLATE_ID)).willReturn(template);
            given(approverRepository.save(any())).willReturn(approver);
            given(templateStepRepository.findByTemplateIdOrderByStepOrderAsc(TEMPLATE_ID))
                    .willReturn(List.of(templateStep));
            given(approverRepository.countByRequestStepId(any())).willReturn(3L);
            // ANY mode: approvedCount > 0 → stepApproved
            given(approverRepository.countByRequestStepIdAndDecision(any(), org.mockito.ArgumentMatchers.eq(ApproverDecision.APPROVED)))
                    .willReturn(1L);
            given(approverRepository.countByRequestStepIdAndDecision(any(), org.mockito.ArgumentMatchers.eq(ApproverDecision.REJECTED)))
                    .willReturn(0L);
            given(requestStepRepository.save(any())).willReturn(currentStep);
            given(requestRepository.save(entity)).willReturn(entity);
            given(requestStepRepository.findByRequestIdOrderByStepOrderAsc(any())).willReturn(List.of());
            given(workflowMapper.toApproverResponseList(any())).willReturn(List.of());
            given(workflowMapper.toRequestDetailResponse(any(), any())).willReturn(response);

            // When
            workflowApprovalService.decide(REQUEST_ID, USER_ID, request);

            // Then: 最終ステップなので承認
            assertThat(entity.getStatus()).isEqualTo(WorkflowStatus.APPROVED);
        }
    }
}
