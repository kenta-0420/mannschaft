package com.mannschaft.app.workflow.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.workflow.WorkflowErrorCode;
import com.mannschaft.app.workflow.WorkflowMapper;
import com.mannschaft.app.workflow.WorkflowScopes;
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
    private final AccessControlService accessControlService;

    /**
     * スコープ内の申請一覧をページング取得する。
     *
     * <p>認可: スコープメンバーのみ（Wave 2 トランシェ2C。非メンバーは 403）。
     * 指定承認者は非 ADMIN メンバーでもあり得るため、メンバー可視とする。</p>
     *
     * @param scopeType   スコープ種別
     * @param scopeId     スコープID
     * @param actorUserId 操作者ユーザーID
     * @param status      ステータスフィルタ（null の場合は全件）
     * @param pageable    ページング情報
     * @return 申請レスポンスのページ
     */
    public Page<WorkflowRequestResponse> listRequests(String scopeType, Long scopeId, Long actorUserId,
                                                      String status, Pageable pageable) {
        accessControlService.checkMembership(actorUserId, scopeId, WorkflowScopes.canonical(scopeType));
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
     * 自分の申請一覧を組織横断的にページング取得する。
     *
     * <p>F05.6 Phase 11 第二陣（2-γ）で追加。MEMBER ロールがフロントエンドの「マイ申請ページ」で
     * 自分の申請をスコープを跨いで一覧表示するためのエンドポイントを支える。</p>
     *
     * @param currentUserId 操作者ユーザー ID
     * @param status        ステータスフィルタ（null の場合は全件）
     * @param pageable      ページング情報
     * @return 申請レスポンスのページ
     */
    public Page<WorkflowRequestResponse> listMyRequests(Long currentUserId, String status, Pageable pageable) {
        Page<WorkflowRequestEntity> page;
        if (status != null) {
            WorkflowStatus workflowStatus = WorkflowStatus.valueOf(status);
            page = requestRepository.findByRequestedByAndStatusOrderByCreatedAtDesc(
                    currentUserId, workflowStatus, pageable);
        } else {
            page = requestRepository.findByRequestedByOrderByCreatedAtDesc(currentUserId, pageable);
        }
        return page.map(this::buildRequestResponse);
    }

    /**
     * 申請詳細を取得する。
     *
     * <p>認可: 申請者本人、または entity 由来スコープのメンバー/ADMIN のみ。
     * それ以外は 404 で存在秘匿（BOLA対策・Wave 2 トランシェ2C）。</p>
     *
     * @param scopeType   スコープ種別
     * @param scopeId     スコープID
     * @param requestId   申請ID
     * @param actorUserId 操作者ユーザーID
     * @return 申請レスポンス
     */
    public WorkflowRequestResponse getRequest(String scopeType, Long scopeId, Long requestId, Long actorUserId) {
        WorkflowRequestEntity entity = findRequestOrThrow(scopeType, scopeId, requestId);
        checkRequestVisibility(entity, actorUserId);
        return buildRequestResponse(entity);
    }

    /**
     * 申請を作成する（下書き状態）— API 公開入口（Wave 2 トランシェ2C）。
     *
     * <p>認可: スコープメンバーのみ（非メンバーは 403）。認可後に {@link #createRequest} へ委譲する。
     * バッチ（シフト予算 100% 到達の自動起動）は操作ユーザーが存在しないため、
     * 認可ゲートを持たない {@link #createRequest} を直接呼ぶ
     * （認可ガードは public 入口に置き、共有メソッドをバッチの巻き添えにしない）。</p>
     *
     * @param scopeType   スコープ種別
     * @param scopeId     スコープID
     * @param actorUserId 申請者（操作者）ユーザーID
     * @param request     作成リクエスト
     * @return 作成された申請レスポンス
     */
    @Transactional
    public WorkflowRequestResponse createRequestAsUser(String scopeType, Long scopeId, Long actorUserId,
                                                       CreateWorkflowRequestRequest request) {
        accessControlService.checkMembership(actorUserId, scopeId, WorkflowScopes.canonical(scopeType));
        return createRequest(scopeType, scopeId, actorUserId, request);
    }

    /**
     * 申請を作成する（下書き状態）— 内部共有入口（バッチ・システム起動用）。
     *
     * <p>テンプレートはスコープ整合を検証する: 指定された templateId が path のスコープに
     * 属さない場合は 404（TEMPLATE_NOT_FOUND）で存在秘匿する（クロススコープのテンプレート
     * 探索・流用の根治。Wave 2 トランシェ2C）。</p>
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param userId    申請者ユーザーID（システム自動起動時は null）
     * @param request   作成リクエスト
     * @return 作成された申請レスポンス
     */
    @Transactional
    public WorkflowRequestResponse createRequest(String scopeType, Long scopeId, Long userId,
                                                  CreateWorkflowRequestRequest request) {
        WorkflowTemplateEntity template = templateService.getTemplateEntity(request.getTemplateId());

        if (!WorkflowScopes.canonical(template.getScopeType()).equals(WorkflowScopes.canonical(scopeType))
                || !template.getScopeId().equals(scopeId)) {
            throw new BusinessException(WorkflowErrorCode.TEMPLATE_NOT_FOUND);
        }

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
     * <p>認可: 申請者本人、または entity 由来スコープの ADMIN/DEPUTY_ADMIN のみ（403）。
     * パスと不一致の requestId は 404 で存在秘匿（BOLA対策）。</p>
     *
     * @param scopeType   スコープ種別
     * @param scopeId     スコープID
     * @param requestId   申請ID
     * @param actorUserId 操作者ユーザーID
     * @param request     更新リクエスト
     * @return 更新された申請レスポンス
     */
    @Transactional
    public WorkflowRequestResponse updateRequest(String scopeType, Long scopeId, Long requestId,
                                                  Long actorUserId, UpdateWorkflowRequestRequest request) {
        WorkflowRequestEntity entity = findRequestOrThrow(scopeType, scopeId, requestId);
        checkOwnerOrAdminOnEntityScope(actorUserId, entity);

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
     * 申請を提出する — API 公開入口（Wave 2 トランシェ2C）。
     *
     * <p>認可: 申請者本人、または entity 由来スコープの ADMIN/DEPUTY_ADMIN のみ（403）。
     * パスと不一致の requestId は 404 で存在秘匿（BOLA対策）。認可後に
     * {@link #submitRequest} へ委譲する。バッチ（シフト予算 100% 到達の自動起動）は
     * 認可ゲートを持たない {@link #submitRequest} を直接呼ぶ。</p>
     *
     * @param scopeType   スコープ種別
     * @param scopeId     スコープID
     * @param requestId   申請ID
     * @param actorUserId 操作者ユーザーID
     * @return 更新された申請レスポンス
     */
    @Transactional
    public WorkflowRequestResponse submitRequestAsUser(String scopeType, Long scopeId, Long requestId,
                                                       Long actorUserId) {
        WorkflowRequestEntity entity = findRequestOrThrow(scopeType, scopeId, requestId);
        checkOwnerOrAdminOnEntityScope(actorUserId, entity);
        return submitRequest(scopeType, scopeId, requestId);
    }

    /**
     * 申請を提出する — 内部共有入口（バッチ・システム起動用）。
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
     * <p>認可: 申請者本人、または entity 由来スコープの ADMIN/DEPUTY_ADMIN のみ（403）。
     * パスと不一致の requestId は 404 で存在秘匿（BOLA対策）。</p>
     *
     * @param scopeType   スコープ種別
     * @param scopeId     スコープID
     * @param requestId   申請ID
     * @param actorUserId 操作者ユーザーID
     * @return 更新された申請レスポンス
     */
    @Transactional
    public WorkflowRequestResponse withdrawRequest(String scopeType, Long scopeId, Long requestId,
                                                   Long actorUserId) {
        WorkflowRequestEntity entity = findRequestOrThrow(scopeType, scopeId, requestId);
        checkOwnerOrAdminOnEntityScope(actorUserId, entity);

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
     * <p>認可: 申請者本人、または entity 由来スコープの ADMIN/DEPUTY_ADMIN のみ（403）。
     * パスと不一致の requestId は 404 で存在秘匿（BOLA対策）。</p>
     *
     * @param scopeType   スコープ種別
     * @param scopeId     スコープID
     * @param requestId   申請ID
     * @param actorUserId 操作者ユーザーID
     */
    @Transactional
    public void deleteRequest(String scopeType, Long scopeId, Long requestId, Long actorUserId) {
        WorkflowRequestEntity entity = findRequestOrThrow(scopeType, scopeId, requestId);
        checkOwnerOrAdminOnEntityScope(actorUserId, entity);
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
     * 申請の可視性を検証する（★BOLA厳禁★・Wave 2 トランシェ2C）。
     *
     * <p>申請者本人・entity 由来スコープのメンバー・ADMIN のいずれでもない場合は
     * 404（REQUEST_NOT_FOUND）で存在秘匿する。ADMIN 判定は user_roles 系統のため、
     * memberships 系統の isMember と両方を見る。</p>
     */
    private void checkRequestVisibility(WorkflowRequestEntity entity, Long actorUserId) {
        if (actorUserId != null && actorUserId.equals(entity.getRequestedBy())) {
            return;
        }
        String canonicalScope = WorkflowScopes.canonical(entity.getScopeType());
        if (accessControlService.isMember(actorUserId, entity.getScopeId(), canonicalScope)
                || accessControlService.isAdminOrAbove(actorUserId, entity.getScopeId(), canonicalScope)) {
            return;
        }
        throw new BusinessException(WorkflowErrorCode.REQUEST_NOT_FOUND);
    }

    /**
     * 申請者本人、または entity 由来スコープの ADMIN/DEPUTY_ADMIN であることを検証する（★BOLA厳禁★）。
     *
     * <p>path で渡された scopeId をそのまま信用せず、取得済み entity の scopeType/scopeId で
     * 認可する。違反時は 403（COMMON_002）。</p>
     */
    private void checkOwnerOrAdminOnEntityScope(Long actorUserId, WorkflowRequestEntity entity) {
        accessControlService.checkOwnerOrAdmin(
                actorUserId, entity.getRequestedBy(),
                entity.getScopeId(), WorkflowScopes.canonical(entity.getScopeType()));
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
