package com.mannschaft.app.workflow.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.workflow.ApprovalType;
import com.mannschaft.app.workflow.ApproverDecision;
import com.mannschaft.app.workflow.WorkflowErrorCode;
import com.mannschaft.app.workflow.WorkflowMapper;
import com.mannschaft.app.workflow.WorkflowStatus;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * ワークフロー承認サービス。承認・却下の判断とステップ進行ロジックを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkflowApprovalService {

    private final WorkflowRequestRepository requestRepository;
    private final WorkflowRequestStepRepository requestStepRepository;
    private final WorkflowRequestApproverRepository approverRepository;
    private final WorkflowTemplateStepRepository templateStepRepository;
    private final WorkflowTemplateService templateService;
    private final WorkflowMapper workflowMapper;

    /**
     * 承認判断を行う。
     *
     * @param requestId 申請ID
     * @param userId    承認者ユーザーID
     * @param request   判断リクエスト
     * @return 更新された申請レスポンス
     */
    @Transactional
    public WorkflowRequestResponse decide(Long requestId, Long userId, ApprovalDecisionRequest request) {
        WorkflowRequestEntity requestEntity = requestRepository.findById(requestId)
                .orElseThrow(() -> new BusinessException(WorkflowErrorCode.REQUEST_NOT_FOUND));

        if (requestEntity.getStatus() != WorkflowStatus.IN_PROGRESS) {
            throw new BusinessException(WorkflowErrorCode.INVALID_STATUS_TRANSITION);
        }

        WorkflowRequestStepEntity currentStep = requestStepRepository
                .findByRequestIdAndStepOrder(requestId, requestEntity.getCurrentStepOrder())
                .orElseThrow(() -> new BusinessException(WorkflowErrorCode.STEP_NOT_FOUND));

        WorkflowRequestApproverEntity approver = approverRepository
                .findByRequestStepIdAndApproverUserId(currentStep.getId(), userId)
                .orElseThrow(() -> new BusinessException(WorkflowErrorCode.NOT_APPROVER));

        if (approver.hasDecided()) {
            throw new BusinessException(WorkflowErrorCode.ALREADY_DECIDED);
        }

        WorkflowTemplateEntity template = templateService.getTemplateEntity(requestEntity.getTemplateId());

        ApproverDecision decision = ApproverDecision.valueOf(request.getDecision());

        if (decision == ApproverDecision.APPROVED) {
            if (template.getIsSealRequired() && request.getSealId() == null) {
                throw new BusinessException(WorkflowErrorCode.SEAL_REQUIRED);
            }
            approver.approve(request.getComment(), request.getSealId());
        } else if (decision == ApproverDecision.REJECTED) {
            approver.reject(request.getComment());
        } else {
            throw new BusinessException(WorkflowErrorCode.INVALID_STATUS_TRANSITION);
        }

        approverRepository.save(approver);

        evaluateStepCompletion(requestEntity, currentStep);

        WorkflowRequestEntity saved = requestRepository.save(requestEntity);
        log.info("ワークフロー承認判断: requestId={}, userId={}, decision={}", requestId, userId, decision);
        return buildRequestResponse(saved);
    }

    /**
     * ステップの完了を評価し、次のステップへ進行または申請を完了する。
     */
    private void evaluateStepCompletion(WorkflowRequestEntity requestEntity,
                                         WorkflowRequestStepEntity currentStep) {
        List<WorkflowTemplateStepEntity> templateSteps =
                templateStepRepository.findByTemplateIdOrderByStepOrderAsc(requestEntity.getTemplateId());

        Optional<WorkflowTemplateStepEntity> currentTemplateStep = templateSteps.stream()
                .filter(s -> s.getStepOrder().equals(currentStep.getStepOrder()))
                .findFirst();

        if (currentTemplateStep.isEmpty()) {
            return;
        }

        ApprovalType approvalType = currentTemplateStep.get().getApprovalType();
        long totalApprovers = approverRepository.countByRequestStepId(currentStep.getId());
        long approvedCount = approverRepository.countByRequestStepIdAndDecision(
                currentStep.getId(), ApproverDecision.APPROVED);
        long rejectedCount = approverRepository.countByRequestStepIdAndDecision(
                currentStep.getId(), ApproverDecision.REJECTED);

        boolean stepApproved = false;
        boolean stepRejected = false;

        if (approvalType == ApprovalType.ALL) {
            stepApproved = approvedCount == totalApprovers;
            stepRejected = rejectedCount > 0;
        } else {
            stepApproved = approvedCount > 0;
            stepRejected = rejectedCount == totalApprovers;
        }

        if (stepRejected) {
            currentStep.reject();
            requestStepRepository.save(currentStep);
            requestEntity.reject();
            return;
        }

        if (stepApproved) {
            currentStep.approve();
            requestStepRepository.save(currentStep);

            Optional<WorkflowTemplateStepEntity> nextTemplateStep = templateSteps.stream()
                    .filter(s -> s.getStepOrder() > currentStep.getStepOrder())
                    .findFirst();

            if (nextTemplateStep.isPresent()) {
                requestEntity.advanceStep(nextTemplateStep.get().getStepOrder());

                WorkflowRequestStepEntity nextStep = requestStepRepository
                        .findByRequestIdAndStepOrder(requestEntity.getId(), nextTemplateStep.get().getStepOrder())
                        .orElseThrow(() -> new BusinessException(WorkflowErrorCode.STEP_NOT_FOUND));

                nextStep.startProgress();
                requestStepRepository.save(nextStep);
            } else {
                requestEntity.approve();
            }
        }
    }

    /**
     * 申請レスポンスを組み立てる。
     */
    private WorkflowRequestResponse buildRequestResponse(WorkflowRequestEntity entity) {
        List<WorkflowRequestStepEntity> steps =
                requestStepRepository.findByRequestIdOrderByStepOrderAsc(entity.getId());

        List<RequestStepResponse> stepResponses = steps.stream()
                .map(step -> {
                    List<WorkflowRequestApproverEntity> approvers =
                            approverRepository.findByRequestStepId(step.getId());
                    return new RequestStepResponse(
                            step.getId(),
                            step.getRequestId(),
                            step.getStepOrder(),
                            step.getStatus().name(),
                            step.getCompletedAt(),
                            step.getCreatedAt(),
                            workflowMapper.toApproverResponseList(approvers));
                })
                .toList();

        return workflowMapper.toRequestDetailResponse(entity, stepResponses);
    }
}
