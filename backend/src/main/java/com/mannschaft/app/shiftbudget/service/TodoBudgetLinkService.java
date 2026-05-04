package com.mannschaft.app.shiftbudget.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.shiftbudget.ShiftBudgetErrorCode;
import com.mannschaft.app.shiftbudget.ShiftBudgetFeatureService;
import com.mannschaft.app.shiftbudget.dto.TodoBudgetLinkCreateRequest;
import com.mannschaft.app.shiftbudget.dto.TodoBudgetLinkResponse;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetAllocationEntity;
import com.mannschaft.app.shiftbudget.entity.TodoBudgetLinkEntity;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetRateQueryRepository;
import com.mannschaft.app.shiftbudget.repository.TodoBudgetLinkRepository;
import com.mannschaft.app.todo.TodoScopeType;
import com.mannschaft.app.todo.entity.ProjectEntity;
import com.mannschaft.app.todo.entity.TodoEntity;
import com.mannschaft.app.todo.repository.ProjectRepository;
import com.mannschaft.app.todo.repository.TodoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * F08.7 TODO/プロジェクト 予算紐付サービス（Phase 9-γ / API #7-#8）。
 *
 * <p>設計書 F08.7 (v1.2) §4.3 / §5.4 / §6.2.4 / §9.1 / §9.5 に準拠。</p>
 *
 * <p><strong>権限</strong>（設計書 §6.1）:</p>
 * <ul>
 *   <li>{@code POST /api/v1/todo-budget/links} — {@code MANAGE_TODO} + {@code BUDGET_VIEW}</li>
 *   <li>{@code DELETE /api/v1/todo-budget/links/{id}} — {@code MANAGE_TODO} + {@code BUDGET_VIEW}</li>
 * </ul>
 *
 * <p>本コードベースには専用の {@code MANAGE_TODO} 権限ロールが存在しないため、
 * 「対象 project/todo のスコープに対する {@code ADMIN_OR_ABOVE}」を以て
 * {@code MANAGE_TODO} 権限保有とみなす（既存 TODO 系 Service の認可パターン踏襲）。</p>
 *
 * <p><strong>多テナント分離</strong>（設計書 §9.5）:</p>
 * <ul>
 *   <li>allocation の {@code organizationId} と認証ユーザーの組織コンテキストが一致すること</li>
 *   <li>project/todo の scope が allocation と同一組織に属すること
 *       （ORGANIZATION スコープは {@code scopeId == organizationId}、
 *        TEAM スコープは team の所属組織 = organizationId、
 *        PERSONAL スコープは Phase 9-γ では非サポート）</li>
 * </ul>
 *
 * <p><strong>監査ログ</strong>（設計書 §9.1）: {@code TODO_BUDGET_LINK_CREATED} /
 * {@code TODO_BUDGET_LINK_DELETED}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TodoBudgetLinkService {

    private static final String DEFAULT_CURRENCY = "JPY";

    private final TodoBudgetLinkRepository linkRepository;
    private final ShiftBudgetAllocationRepository allocationRepository;
    private final ShiftBudgetRateQueryRepository rateQueryRepository;
    private final ProjectRepository projectRepository;
    private final TodoRepository todoRepository;
    private final ShiftBudgetFeatureService featureService;
    private final AccessControlService accessControlService;
    private final AuditLogService auditLogService;

    // ====================================================================
    // 紐付作成 #7
    // ====================================================================

    /**
     * 新規紐付を作成する。
     *
     * <p>処理順序:</p>
     * <ol>
     *   <li>フィーチャーフラグ判定</li>
     *   <li>排他バリデーション（project/todo XOR、link_amount/percentage XOR）</li>
     *   <li>allocation の存在 + 組織所属検証</li>
     *   <li>project / todo の存在 + 組織所属検証 + スコープ ADMIN_OR_ABOVE 権限検証</li>
     *   <li>BUDGET_VIEW 権限検証（allocation 組織スコープ）</li>
     *   <li>同一 (project_id, allocation_id) または (todo_id, allocation_id) 重複チェック</li>
     *   <li>INSERT</li>
     *   <li>監査ログ {@code TODO_BUDGET_LINK_CREATED}</li>
     * </ol>
     */
    @Transactional
    public TodoBudgetLinkResponse createLink(Long organizationId, TodoBudgetLinkCreateRequest request) {
        featureService.requireEnabled(organizationId);
        validateLinkTargetXor(request);
        validateLinkParameterXor(request);
        validateLinkAmountSign(request);

        // (1) allocation の存在 + 組織所属検証
        ShiftBudgetAllocationEntity allocation = allocationRepository
                .findByIdAndOrganizationIdAndDeletedAtIsNull(request.allocationId(), organizationId)
                .orElseThrow(() -> new BusinessException(ShiftBudgetErrorCode.ALLOCATION_NOT_FOUND));

        // (2) project / todo の検証 + 認可（MANAGE_TODO 相当）
        Long currentUserId = SecurityUtils.getCurrentUserId();
        if (request.projectId() != null) {
            ProjectEntity project = findProjectInOrganizationOrThrow(request.projectId(), organizationId);
            requireScopeAdminOrAbove(currentUserId, project.getScopeType(), project.getScopeId());
        } else {
            TodoEntity todo = findTodoInOrganizationOrThrow(request.todoId(), organizationId);
            requireScopeAdminOrAbove(currentUserId, todo.getScopeType(), todo.getScopeId());
        }

        // (3) BUDGET_VIEW 権限検証（allocation 組織スコープ）
        requireBudgetView(currentUserId, organizationId);

        // (4) 同一 (project_id|todo_id, allocation_id) 重複チェック
        if (request.projectId() != null) {
            Optional<TodoBudgetLinkEntity> existing = linkRepository
                    .findByProjectIdAndAllocationId(request.projectId(), request.allocationId());
            if (existing.isPresent()) {
                throw new BusinessException(ShiftBudgetErrorCode.LINK_ALREADY_EXISTS);
            }
        } else {
            Optional<TodoBudgetLinkEntity> existing = linkRepository
                    .findByTodoIdAndAllocationId(request.todoId(), request.allocationId());
            if (existing.isPresent()) {
                throw new BusinessException(ShiftBudgetErrorCode.LINK_ALREADY_EXISTS);
            }
        }

        // (5) INSERT
        TodoBudgetLinkEntity entity = TodoBudgetLinkEntity.builder()
                .projectId(request.projectId())
                .todoId(request.todoId())
                .allocationId(allocation.getId())
                .linkAmount(request.linkAmount())
                .linkPercentage(request.linkPercentage())
                .currency(request.currency() != null ? request.currency() : DEFAULT_CURRENCY)
                .createdBy(currentUserId)
                .build();
        entity = linkRepository.save(entity);

        log.info("TODO 予算紐付を作成しました: id={}, projectId={}, todoId={}, allocationId={}, "
                        + "linkAmount={}, linkPercentage={}",
                entity.getId(), request.projectId(), request.todoId(),
                request.allocationId(), request.linkAmount(), request.linkPercentage());

        // (6) 監査ログ
        auditLogService.record(
                "TODO_BUDGET_LINK_CREATED",
                currentUserId, null,
                allocation.getTeamId(), organizationId,
                null, null, null,
                buildCreateMetadata(entity));

        return TodoBudgetLinkResponse.from(entity);
    }

    // ====================================================================
    // 紐付削除 #8
    // ====================================================================

    /**
     * 紐付を物理削除する（論理削除なし、設計書 §5.4 「論理削除なし」）。
     *
     * <p>処理順序:</p>
     * <ol>
     *   <li>フィーチャーフラグ判定</li>
     *   <li>多テナント分離付き取得（{@code findByIdAndOrganizationId}）</li>
     *   <li>scope の認可検証 + BUDGET_VIEW 権限検証</li>
     *   <li>DELETE</li>
     *   <li>監査ログ {@code TODO_BUDGET_LINK_DELETED}</li>
     * </ol>
     */
    @Transactional
    public void deleteLink(Long organizationId, Long linkId) {
        featureService.requireEnabled(organizationId);

        TodoBudgetLinkEntity link = linkRepository.findByIdAndOrganizationId(linkId, organizationId)
                .orElseThrow(() -> new BusinessException(ShiftBudgetErrorCode.LINK_NOT_FOUND));

        // 認可: project/todo のスコープに対する ADMIN_OR_ABOVE
        Long currentUserId = SecurityUtils.getCurrentUserId();
        if (link.getProjectId() != null) {
            ProjectEntity project = findProjectInOrganizationOrThrow(link.getProjectId(), organizationId);
            requireScopeAdminOrAbove(currentUserId, project.getScopeType(), project.getScopeId());
        } else if (link.getTodoId() != null) {
            TodoEntity todo = findTodoInOrganizationOrThrow(link.getTodoId(), organizationId);
            requireScopeAdminOrAbove(currentUserId, todo.getScopeType(), todo.getScopeId());
        }
        requireBudgetView(currentUserId, organizationId);

        // allocation の team_id を監査ログに含めるため、削除前に取得
        Long teamId = allocationRepository
                .findByIdAndOrganizationIdAndDeletedAtIsNull(link.getAllocationId(), organizationId)
                .map(ShiftBudgetAllocationEntity::getTeamId)
                .orElse(null);

        linkRepository.delete(link);

        log.info("TODO 予算紐付を削除しました: id={}, projectId={}, todoId={}, allocationId={}",
                linkId, link.getProjectId(), link.getTodoId(), link.getAllocationId());

        auditLogService.record(
                "TODO_BUDGET_LINK_DELETED",
                currentUserId, null,
                teamId, organizationId,
                null, null, null,
                String.format("{\"link_id\":%d,\"project_id\":%s,\"todo_id\":%s,\"allocation_id\":%d}",
                        linkId,
                        link.getProjectId() != null ? link.getProjectId().toString() : "null",
                        link.getTodoId() != null ? link.getTodoId().toString() : "null",
                        link.getAllocationId()));
    }

    // ====================================================================
    // バリデーション
    // ====================================================================

    /**
     * project_id と todo_id がどちらか一方のみ NOT NULL であることを検証する。
     */
    private void validateLinkTargetXor(TodoBudgetLinkCreateRequest request) {
        boolean hasProject = request.projectId() != null;
        boolean hasTodo = request.todoId() != null;
        if (hasProject == hasTodo) {
            // 両方 NULL or 両方 NOT NULL → 不正
            throw new BusinessException(ShiftBudgetErrorCode.INVALID_LINK_TARGET);
        }
    }

    /**
     * link_amount と link_percentage が同時指定でないことを検証する（両方 NULL は許容）。
     */
    private void validateLinkParameterXor(TodoBudgetLinkCreateRequest request) {
        if (request.linkAmount() != null && request.linkPercentage() != null) {
            throw new BusinessException(ShiftBudgetErrorCode.INVALID_LINK_PARAMETER);
        }
    }

    private void validateLinkAmountSign(TodoBudgetLinkCreateRequest request) {
        if (request.linkAmount() != null && request.linkAmount().signum() < 0) {
            throw new BusinessException(ShiftBudgetErrorCode.INVALID_LINK_PARAMETER);
        }
    }

    // ====================================================================
    // 多テナント検証
    // ====================================================================

    /**
     * 指定 projectId が存在し、かつ指定組織に属することを検証する。
     *
     * <p>所属判定:</p>
     * <ul>
     *   <li>{@code scopeType = ORGANIZATION} → {@code scopeId == organizationId}</li>
     *   <li>{@code scopeType = TEAM} → team の所属組織 = organizationId</li>
     *   <li>{@code scopeType = PERSONAL} → Phase 9-γ では非サポート（PROJECT_NOT_FOUND を返す）</li>
     * </ul>
     */
    private ProjectEntity findProjectInOrganizationOrThrow(Long projectId, Long organizationId) {
        ProjectEntity project = projectRepository.findByIdAndDeletedAtIsNull(projectId)
                .orElseThrow(() -> new BusinessException(ShiftBudgetErrorCode.PROJECT_NOT_FOUND));
        if (!isScopeInOrganization(project.getScopeType(), project.getScopeId(), organizationId)) {
            throw new BusinessException(ShiftBudgetErrorCode.PROJECT_NOT_FOUND);
        }
        return project;
    }

    /**
     * 指定 todoId が存在し、かつ指定組織に属することを検証する。
     */
    private TodoEntity findTodoInOrganizationOrThrow(Long todoId, Long organizationId) {
        TodoEntity todo = todoRepository.findByIdAndDeletedAtIsNull(todoId)
                .orElseThrow(() -> new BusinessException(ShiftBudgetErrorCode.TODO_NOT_FOUND));
        if (!isScopeInOrganization(todo.getScopeType(), todo.getScopeId(), organizationId)) {
            throw new BusinessException(ShiftBudgetErrorCode.TODO_NOT_FOUND);
        }
        return todo;
    }

    /**
     * 指定スコープ (scopeType, scopeId) が指定組織に属するかを判定する。
     *
     * <p>PERSONAL スコープは予算紐付の対象外（個人タスクに組織予算を紐付けるユースケースが
     * 設計書 §3 UC-3 に存在しない）。</p>
     */
    private boolean isScopeInOrganization(TodoScopeType scopeType, Long scopeId, Long organizationId) {
        if (scopeType == TodoScopeType.ORGANIZATION) {
            return scopeId.equals(organizationId);
        }
        if (scopeType == TodoScopeType.TEAM) {
            return rateQueryRepository.findOrganizationIdByTeamId(scopeId)
                    .map(orgId -> orgId.equals(organizationId))
                    .orElse(false);
        }
        // PERSONAL は対象外
        return false;
    }

    // ====================================================================
    // 認可
    // ====================================================================

    /**
     * project/todo のスコープに対する {@code ADMIN_OR_ABOVE} 権限を要求する
     * （設計書 §6.1 {@code MANAGE_TODO} 相当、コードベースの既存 TODO 系認可パターン踏襲）。
     *
     * <p>SYSTEM_ADMIN は常に許可。</p>
     */
    private void requireScopeAdminOrAbove(Long userId, TodoScopeType scopeType, Long scopeId) {
        if (accessControlService.isSystemAdmin(userId)) {
            return;
        }
        if (!accessControlService.isAdminOrAbove(userId, scopeId, scopeType.name())) {
            throw new BusinessException(ShiftBudgetErrorCode.LINK_PERMISSION_REQUIRED);
        }
    }

    /**
     * 組織スコープに対する {@code BUDGET_VIEW} 権限を要求する。
     */
    private void requireBudgetView(Long userId, Long organizationId) {
        if (accessControlService.isSystemAdmin(userId)) {
            return;
        }
        if (!accessControlService.isMember(userId, organizationId, "ORGANIZATION")) {
            throw new BusinessException(ShiftBudgetErrorCode.LINK_PERMISSION_REQUIRED);
        }
        try {
            accessControlService.checkPermission(userId, organizationId, "ORGANIZATION", "BUDGET_VIEW");
        } catch (BusinessException e) {
            throw new BusinessException(ShiftBudgetErrorCode.LINK_PERMISSION_REQUIRED);
        }
    }

    // ====================================================================
    // ヘルパー
    // ====================================================================

    private String buildCreateMetadata(TodoBudgetLinkEntity entity) {
        return String.format(
                "{\"link_id\":%d,\"project_id\":%s,\"todo_id\":%s,\"allocation_id\":%d,"
                        + "\"link_amount\":%s,\"link_percentage\":%s}",
                entity.getId(),
                entity.getProjectId() != null ? entity.getProjectId().toString() : "null",
                entity.getTodoId() != null ? entity.getTodoId().toString() : "null",
                entity.getAllocationId(),
                entity.getLinkAmount() != null ? entity.getLinkAmount().toPlainString() : "null",
                entity.getLinkPercentage() != null ? entity.getLinkPercentage().toPlainString() : "null");
    }
}
