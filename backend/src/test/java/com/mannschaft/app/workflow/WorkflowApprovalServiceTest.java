package com.mannschaft.app.workflow;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.workflow.dto.ApprovalDecisionRequest;
import com.mannschaft.app.workflow.entity.WorkflowRequestApproverEntity;
import com.mannschaft.app.workflow.entity.WorkflowRequestEntity;
import com.mannschaft.app.workflow.entity.WorkflowRequestStepEntity;
import com.mannschaft.app.workflow.entity.WorkflowTemplateEntity;
import com.mannschaft.app.workflow.repository.WorkflowRequestApproverRepository;
import com.mannschaft.app.workflow.repository.WorkflowRequestRepository;
import com.mannschaft.app.workflow.repository.WorkflowRequestStepRepository;
import com.mannschaft.app.workflow.service.WorkflowApprovalService;
import com.mannschaft.app.workflow.service.WorkflowTemplateService;
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
    private WorkflowTemplateService templateService;

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
    }
}
