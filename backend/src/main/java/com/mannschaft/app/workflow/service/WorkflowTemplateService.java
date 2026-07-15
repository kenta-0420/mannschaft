package com.mannschaft.app.workflow.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.workflow.ApprovalType;
import com.mannschaft.app.workflow.ApproverType;
import com.mannschaft.app.workflow.WorkflowErrorCode;
import com.mannschaft.app.workflow.WorkflowFieldType;
import com.mannschaft.app.workflow.WorkflowMapper;
import com.mannschaft.app.workflow.WorkflowScopes;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * ワークフローテンプレートサービス。テンプレートのCRUD・有効化/無効化を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkflowTemplateService {

    private final WorkflowTemplateRepository templateRepository;
    private final WorkflowTemplateStepRepository stepRepository;
    private final WorkflowTemplateFieldRepository fieldRepository;
    private final WorkflowMapper workflowMapper;
    private final AccessControlService accessControlService;

    /**
     * スコープ内のテンプレート一覧をページング取得する。
     *
     * <p>認可: スコープメンバーのみ（Wave 2 トランシェ2C。非メンバーは 403）。</p>
     *
     * @param scopeType   スコープ種別
     * @param scopeId     スコープID
     * @param actorUserId 操作者ユーザーID
     * @param pageable    ページング情報
     * @return テンプレートレスポンスのページ
     */
    public Page<WorkflowTemplateResponse> listTemplates(String scopeType, Long scopeId, Long actorUserId,
                                                        Pageable pageable) {
        accessControlService.checkMembership(actorUserId, scopeId, WorkflowScopes.canonical(scopeType));
        return templateRepository.findByScopeTypeAndScopeIdOrderBySortOrderAsc(scopeType, scopeId, pageable)
                .map(entity -> {
                    List<WorkflowTemplateStepEntity> steps = stepRepository.findByTemplateIdOrderByStepOrderAsc(entity.getId());
                    List<WorkflowTemplateFieldEntity> fields = fieldRepository.findByTemplateIdOrderBySortOrderAsc(entity.getId());
                    return workflowMapper.toTemplateDetailResponse(entity, steps, fields);
                });
    }

    /**
     * スコープ内の有効なテンプレート一覧を取得する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @return 有効なテンプレートレスポンスリスト
     */
    public List<WorkflowTemplateResponse> listActiveTemplates(String scopeType, Long scopeId) {
        return templateRepository.findByScopeTypeAndScopeIdAndIsActiveTrueOrderBySortOrderAsc(scopeType, scopeId)
                .stream()
                .map(entity -> {
                    List<WorkflowTemplateStepEntity> steps = stepRepository.findByTemplateIdOrderByStepOrderAsc(entity.getId());
                    List<WorkflowTemplateFieldEntity> fields = fieldRepository.findByTemplateIdOrderBySortOrderAsc(entity.getId());
                    return workflowMapper.toTemplateDetailResponse(entity, steps, fields);
                })
                .toList();
    }

    /**
     * テンプレート詳細を取得する。
     *
     * <p>認可: スコープメンバーのみ（403）。パスと不一致の templateId は 404 で存在秘匿（BOLA対策）。</p>
     *
     * @param scopeType   スコープ種別
     * @param scopeId     スコープID
     * @param templateId  テンプレートID
     * @param actorUserId 操作者ユーザーID
     * @return テンプレートレスポンス
     */
    public WorkflowTemplateResponse getTemplate(String scopeType, Long scopeId, Long templateId, Long actorUserId) {
        accessControlService.checkMembership(actorUserId, scopeId, WorkflowScopes.canonical(scopeType));
        WorkflowTemplateEntity entity = findTemplateOrThrow(scopeType, scopeId, templateId);
        List<WorkflowTemplateStepEntity> steps = stepRepository.findByTemplateIdOrderByStepOrderAsc(templateId);
        List<WorkflowTemplateFieldEntity> fields = fieldRepository.findByTemplateIdOrderBySortOrderAsc(templateId);
        return workflowMapper.toTemplateDetailResponse(entity, steps, fields);
    }

    /**
     * テンプレートを作成する。
     *
     * <p>認可: スコープの ADMIN/DEPUTY_ADMIN のみ（非ADMINは 403）。</p>
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param userId    作成者ユーザーID
     * @param request   作成リクエスト
     * @return 作成されたテンプレートレスポンス
     */
    @Transactional
    public WorkflowTemplateResponse createTemplate(String scopeType, Long scopeId, Long userId,
                                                    CreateWorkflowTemplateRequest request) {
        accessControlService.checkAdminOrAbove(userId, scopeId, WorkflowScopes.canonical(scopeType));
        WorkflowTemplateEntity entity = WorkflowTemplateEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .name(request.getName())
                .description(request.getDescription())
                .icon(request.getIcon())
                .color(request.getColor())
                .isSealRequired(request.getIsSealRequired())
                .sortOrder(request.getSortOrder() != null ? request.getSortOrder() : 0)
                .createdBy(userId)
                .build();

        WorkflowTemplateEntity saved = templateRepository.save(entity);

        List<WorkflowTemplateStepEntity> steps = saveSteps(saved.getId(), request.getSteps());
        List<WorkflowTemplateFieldEntity> fields = saveFields(saved.getId(), request.getFields());

        log.info("ワークフローテンプレート作成: scopeType={}, scopeId={}, templateId={}", scopeType, scopeId, saved.getId());
        return workflowMapper.toTemplateDetailResponse(saved, steps, fields);
    }

    /**
     * テンプレートを更新する。
     *
     * <p>認可: entity 由来スコープの ADMIN/DEPUTY_ADMIN のみ（403）。
     * パスと不一致の templateId は 404 で存在秘匿（BOLA対策）。</p>
     *
     * @param scopeType   スコープ種別
     * @param scopeId     スコープID
     * @param templateId  テンプレートID
     * @param actorUserId 操作者ユーザーID
     * @param request     更新リクエスト
     * @return 更新されたテンプレートレスポンス
     */
    @Transactional
    public WorkflowTemplateResponse updateTemplate(String scopeType, Long scopeId, Long templateId,
                                                    Long actorUserId, UpdateWorkflowTemplateRequest request) {
        WorkflowTemplateEntity entity = findTemplateOrThrow(scopeType, scopeId, templateId);
        checkAdminOnEntityScope(actorUserId, entity);

        entity.update(
                request.getName(),
                request.getDescription(),
                request.getIcon(),
                request.getColor(),
                request.getIsSealRequired(),
                request.getSortOrder() != null ? request.getSortOrder() : 0);

        WorkflowTemplateEntity saved = templateRepository.save(entity);

        stepRepository.deleteByTemplateId(templateId);
        fieldRepository.deleteByTemplateId(templateId);

        List<WorkflowTemplateStepEntity> steps = saveSteps(templateId, request.getSteps());
        List<WorkflowTemplateFieldEntity> fields = saveFields(templateId, request.getFields());

        log.info("ワークフローテンプレート更新: templateId={}", templateId);
        return workflowMapper.toTemplateDetailResponse(saved, steps, fields);
    }

    /**
     * テンプレートを論理削除する。
     *
     * <p>認可: entity 由来スコープの ADMIN/DEPUTY_ADMIN のみ（403）。
     * パスと不一致の templateId は 404 で存在秘匿（BOLA対策）。</p>
     *
     * @param scopeType   スコープ種別
     * @param scopeId     スコープID
     * @param templateId  テンプレートID
     * @param actorUserId 操作者ユーザーID
     */
    @Transactional
    public void deleteTemplate(String scopeType, Long scopeId, Long templateId, Long actorUserId) {
        WorkflowTemplateEntity entity = findTemplateOrThrow(scopeType, scopeId, templateId);
        checkAdminOnEntityScope(actorUserId, entity);
        entity.softDelete();
        templateRepository.save(entity);
        log.info("ワークフローテンプレート削除: templateId={}", templateId);
    }

    /**
     * テンプレートを有効化する。
     *
     * <p>認可: entity 由来スコープの ADMIN/DEPUTY_ADMIN のみ（403）。</p>
     *
     * @param scopeType   スコープ種別
     * @param scopeId     スコープID
     * @param templateId  テンプレートID
     * @param actorUserId 操作者ユーザーID
     * @return 更新されたテンプレートレスポンス
     */
    @Transactional
    public WorkflowTemplateResponse activateTemplate(String scopeType, Long scopeId, Long templateId,
                                                     Long actorUserId) {
        WorkflowTemplateEntity entity = findTemplateOrThrow(scopeType, scopeId, templateId);
        checkAdminOnEntityScope(actorUserId, entity);
        entity.activate();
        WorkflowTemplateEntity saved = templateRepository.save(entity);
        List<WorkflowTemplateStepEntity> steps = stepRepository.findByTemplateIdOrderByStepOrderAsc(templateId);
        List<WorkflowTemplateFieldEntity> fields = fieldRepository.findByTemplateIdOrderBySortOrderAsc(templateId);
        log.info("ワークフローテンプレート有効化: templateId={}", templateId);
        return workflowMapper.toTemplateDetailResponse(saved, steps, fields);
    }

    /**
     * テンプレートを無効化する。
     *
     * <p>認可: entity 由来スコープの ADMIN/DEPUTY_ADMIN のみ（403）。</p>
     *
     * @param scopeType   スコープ種別
     * @param scopeId     スコープID
     * @param templateId  テンプレートID
     * @param actorUserId 操作者ユーザーID
     * @return 更新されたテンプレートレスポンス
     */
    @Transactional
    public WorkflowTemplateResponse deactivateTemplate(String scopeType, Long scopeId, Long templateId,
                                                       Long actorUserId) {
        WorkflowTemplateEntity entity = findTemplateOrThrow(scopeType, scopeId, templateId);
        checkAdminOnEntityScope(actorUserId, entity);
        entity.deactivate();
        WorkflowTemplateEntity saved = templateRepository.save(entity);
        List<WorkflowTemplateStepEntity> steps = stepRepository.findByTemplateIdOrderByStepOrderAsc(templateId);
        List<WorkflowTemplateFieldEntity> fields = fieldRepository.findByTemplateIdOrderBySortOrderAsc(templateId);
        log.info("ワークフローテンプレート無効化: templateId={}", templateId);
        return workflowMapper.toTemplateDetailResponse(saved, steps, fields);
    }

    /**
     * テンプレートエンティティを取得する（他サービスから利用）。
     *
     * @param templateId テンプレートID
     * @return テンプレートエンティティ
     */
    public WorkflowTemplateEntity getTemplateEntity(Long templateId) {
        return templateRepository.findById(templateId)
                .orElseThrow(() -> new BusinessException(WorkflowErrorCode.TEMPLATE_NOT_FOUND));
    }

    /**
     * entity 由来のスコープで ADMIN/DEPUTY_ADMIN であることを検証する（★BOLA厳禁★）。
     *
     * <p>path で渡された scopeId をそのまま信用せず、取得済み entity の scopeType/scopeId で
     * 認可する（Wave 2 トランシェ2C）。違反時は 403（COMMON_002）。</p>
     */
    private void checkAdminOnEntityScope(Long actorUserId, WorkflowTemplateEntity entity) {
        accessControlService.checkAdminOrAbove(
                actorUserId, entity.getScopeId(), WorkflowScopes.canonical(entity.getScopeType()));
    }

    /**
     * テンプレートを取得する。存在しない場合は例外をスローする。
     */
    private WorkflowTemplateEntity findTemplateOrThrow(String scopeType, Long scopeId, Long templateId) {
        return templateRepository.findByIdAndScopeTypeAndScopeId(templateId, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(WorkflowErrorCode.TEMPLATE_NOT_FOUND));
    }

    /**
     * ステップ定義を保存する。
     */
    private List<WorkflowTemplateStepEntity> saveSteps(Long templateId, List<TemplateStepRequest> stepRequests) {
        if (stepRequests == null || stepRequests.isEmpty()) {
            return List.of();
        }

        List<WorkflowTemplateStepEntity> steps = stepRequests.stream()
                .map(req -> (WorkflowTemplateStepEntity) WorkflowTemplateStepEntity.builder()
                        .templateId(templateId)
                        .stepOrder(req.getStepOrder())
                        .name(req.getName())
                        .approvalType(ApprovalType.valueOf(req.getApprovalType()))
                        .approverType(ApproverType.valueOf(req.getApproverType()))
                        .approverUserIds(req.getApproverUserIds())
                        .approverRole(req.getApproverRole())
                        .autoApproveDays(req.getAutoApproveDays())
                        .build())
                .toList();

        return stepRepository.saveAll(steps);
    }

    /**
     * フィールド定義を保存する。
     */
    private List<WorkflowTemplateFieldEntity> saveFields(Long templateId, List<TemplateFieldRequest> fieldRequests) {
        if (fieldRequests == null || fieldRequests.isEmpty()) {
            return List.of();
        }

        List<WorkflowTemplateFieldEntity> fields = fieldRequests.stream()
                .map(req -> (WorkflowTemplateFieldEntity) WorkflowTemplateFieldEntity.builder()
                        .templateId(templateId)
                        .fieldKey(req.getFieldKey())
                        .fieldLabel(req.getFieldLabel())
                        .fieldType(WorkflowFieldType.valueOf(req.getFieldType()))
                        .isRequired(req.getIsRequired())
                        .sortOrder(req.getSortOrder() != null ? req.getSortOrder() : 0)
                        .optionsJson(req.getOptionsJson())
                        .build())
                .toList();

        return fieldRepository.saveAll(fields);
    }
}
