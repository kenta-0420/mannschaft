package com.mannschaft.app.workflow.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.workflow.WorkflowErrorCode;
import com.mannschaft.app.workflow.WorkflowMapper;
import com.mannschaft.app.workflow.WorkflowStatus;
import com.mannschaft.app.workflow.dto.CreateWorkflowRequestRequest;
import com.mannschaft.app.workflow.dto.RequestStepResponse;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * ワークフロー申請サービス。申請のCRUD・提出・取り下げ・ステータス管理を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkflowRequestService {

    private final WorkflowRequestRepository requestRepository;
    private final WorkflowRequestStepRepository requestStepRepository;
    private final WorkflowRequestApproverRepository approverRepository;
    private final WorkflowTemplateStepRepository templateStepRepository;
    private final WorkflowTemplateService templateService;
    private final WorkflowMapper workflowMapper;

    /**
     * スコープ内の申請一覧をページング取得する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param status    ステータスフィルタ（null の場合は全件）
     * @param pageable  ページング情報
     * @return 申請レスポンスのページ
     */
    public Page<WorkflowRequestResponse> listRequests(String scopeType, Long scopeId, String status, Pageable pageable) {
        Page<WorkflowRequestEntity> page;
        if (status != null) {
            WorkflowStatus workflowStatus = WorkflowStatus.valueOf(status);
            page = requestRepository.findByScopeTypeAndScopeIdAndStatusOrderByCreatedAtDesc(
                    scopeType, scopeId, workflowStatus, pageable);
        } else {
            page = requestRepository.findByScopeTypeAndScopeIdOrderByCreatedAtDesc(scopeType, scopeId, pageable);
        }
        return page.map(this::buildRequestResponse);
    }

    /**
     * 申請詳細を取得する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param requestId 申請ID
     * @return 申請レスポンス
     */
    public WorkflowRequestResponse getRequest(String scopeType, Long scopeId, Long requestId) {
        WorkflowRequestEntity entity = findRequestOrThrow(scopeType, scopeId, requestId);
        return buildRequestResponse(entity);
    }

    /**
     * 申請を作成する（下書き状態）。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param userId    申請者ユーザーID
     * @param request   作成リクエスト
     * @return 作成された申請レスポンス
     */
    @Transactional
    public WorkflowRequestResponse createRequest(String scopeType, Long scopeId, Long userId,
                                                  CreateWorkflowRequestRequest request) {
        WorkflowTemplateEntity template = templateService.getTemplateEntity(request.getTemplateId());

        if (!template.getIsActive()) {
            throw new BusinessException(WorkflowErrorCode.TEMPLATE_INACTIVE);
        }

        WorkflowRequestEntity entity = WorkflowRequestEntity.builder()
                .templateId(request.getTemplateId())
                .scopeType(scopeType)
                .scopeId(scopeId)
                .title(request.getTitle())
                .requestedBy(userId)
                .fieldValues(request.getFieldValues())
                .sourceType(request.getSourceType())
                .sourceId(request.getSourceId())
                .build();

        WorkflowRequestEntity saved = requestRepository.save(entity);

        log.info("ワークフロー申請作成: scopeType={}, scopeId={}, requestId={}", scopeType, scopeId, saved.getId());
        return buildRequestResponse(saved);
    }

    /**
     * 申請を更新する（下書き状態のみ）。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param requestId 申請ID
     * @param request   更新リクエスト
     * @return 更新された申請レスポンス
     */
    @Transactional
    public WorkflowRequestResponse updateRequest(String scopeType, Long scopeId, Long requestId,
                                                  UpdateWorkflowRequestRequest request) {
        WorkflowRequestEntity entity = findRequestOrThrow(scopeType, scopeId, requestId);

        if (entity.getStatus() != WorkflowStatus.DRAFT) {
            throw new BusinessException(WorkflowErrorCode.INVALID_STATUS_TRANSITION);
        }

        entity.updateTitle(request.getTitle());
        entity.updateFieldValues(request.getFieldValues());

        WorkflowRequestEntity saved = requestRepository.save(entity);
        log.info("ワークフロー申請更新: requestId={}", requestId);
        return buildRequestResponse(saved);
    }

    /**
     * 申請を提出する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param requestId 申請ID
     * @return 更新された申請レスポンス
     */
    @Transactional
    public WorkflowRequestResponse submitRequest(String scopeType, Long scopeId, Long requestId) {
        WorkflowRequestEntity entity = findRequestOrThrow(scopeType, scopeId, requestId);

        if (!entity.isSubmittable()) {
            throw new BusinessException(WorkflowErrorCode.INVALID_STATUS_TRANSITION);
        }

        entity.submit();

        List<WorkflowTemplateStepEntity> templateSteps =
                templateStepRepository.findByTemplateIdOrderByStepOrderAsc(entity.getTemplateId());

        for (WorkflowTemplateStepEntity templateStep : templateSteps) {
            WorkflowRequestStepEntity requestStep = WorkflowRequestStepEntity.builder()
                    .requestId(entity.getId())
                    .stepOrder(templateStep.getStepOrder())
                    .build();
            WorkflowRequestStepEntity savedStep = requestStepRepository.save(requestStep);

            if (templateStep.getApproverUserIds() != null) {
                createApproversFromJson(savedStep.getId(), templateStep.getApproverUserIds());
            }
        }

        if (!templateSteps.isEmpty()) {
            entity.startProgress();
            WorkflowRequestStepEntity firstStep =
                    requestStepRepository.findByRequestIdAndStepOrder(entity.getId(), 1)
                            .orElseThrow(() -> new BusinessException(WorkflowErrorCode.STEP_NOT_FOUND));
            firstStep.startProgress();
            requestStepRepository.save(firstStep);
        }

        WorkflowRequestEntity saved = requestRepository.save(entity);
        log.info("ワークフロー申請提出: requestId={}", requestId);
        return buildRequestResponse(saved);
    }

    /**
     * 申請を取り下げる。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param requestId 申請ID
     * @return 更新された申請レスポンス
     */
    @Transactional
    public WorkflowRequestResponse withdrawRequest(String scopeType, Long scopeId, Long requestId) {
        WorkflowRequestEntity entity = findRequestOrThrow(scopeType, scopeId, requestId);

        if (!entity.isWithdrawable()) {
            throw new BusinessException(WorkflowErrorCode.INVALID_STATUS_TRANSITION);
        }

        entity.withdraw();
        WorkflowRequestEntity saved = requestRepository.save(entity);
        log.info("ワークフロー申請取り下げ: requestId={}", requestId);
        return buildRequestResponse(saved);
    }

    /**
     * 申請を論理削除する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param requestId 申請ID
     */
    @Transactional
    public void deleteRequest(String scopeType, Long scopeId, Long requestId) {
        WorkflowRequestEntity entity = findRequestOrThrow(scopeType, scopeId, requestId);
        entity.softDelete();
        requestRepository.save(entity);
        log.info("ワークフロー申請削除: requestId={}", requestId);
    }

    /**
     * 申請エンティティを取得する（他サービスから利用）。
     *
     * @param requestId 申請ID
     * @return 申請エンティティ
     */
    public WorkflowRequestEntity getRequestEntity(Long requestId) {
        return requestRepository.findById(requestId)
                .orElseThrow(() -> new BusinessException(WorkflowErrorCode.REQUEST_NOT_FOUND));
    }

    /**
     * 申請を取得する。存在しない場合は例外をスローする。
     */
    private WorkflowRequestEntity findRequestOrThrow(String scopeType, Long scopeId, Long requestId) {
        return requestRepository.findByIdAndScopeTypeAndScopeId(requestId, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(WorkflowErrorCode.REQUEST_NOT_FOUND));
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

    /**
     * JSON形式の承認者ユーザーIDリストから承認者レコードを作成する。
     */
    private void createApproversFromJson(Long requestStepId, String approverUserIdsJson) {
        String cleaned = approverUserIdsJson.replaceAll("[\\[\\]\\s]", "");
        if (cleaned.isEmpty()) {
            return;
        }
        String[] ids = cleaned.split(",");
        for (String idStr : ids) {
            Long userId = Long.parseLong(idStr.trim());
            WorkflowRequestApproverEntity approver = WorkflowRequestApproverEntity.builder()
                    .requestStepId(requestStepId)
                    .approverUserId(userId)
                    .build();
            approverRepository.save(approver);
        }
    }
}
