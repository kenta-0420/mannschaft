package com.mannschaft.app.todo.service;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.todo.TodoErrorCode;
import com.mannschaft.app.todo.TodoPriority;
import com.mannschaft.app.todo.TodoScopeType;
import com.mannschaft.app.todo.TodoStatus;
import com.mannschaft.app.todo.dto.AddAssigneeRequest;
import com.mannschaft.app.todo.dto.AssigneeResponse;
import com.mannschaft.app.todo.dto.BulkStatusChangeRequest;
import com.mannschaft.app.todo.dto.CreateTodoRequest;
import com.mannschaft.app.todo.dto.ProjectResponse;
import com.mannschaft.app.todo.dto.TodoResponse;
import com.mannschaft.app.todo.dto.TodoStatusChangeRequest;
import com.mannschaft.app.todo.dto.TodoStatusChangeResponse;
import com.mannschaft.app.todo.dto.PatchTodoRequest;
import com.mannschaft.app.todo.dto.UpdateTodoRequest;
import com.mannschaft.app.todo.entity.ProjectEntity;
import com.mannschaft.app.todo.entity.ProjectMilestoneEntity;
import com.mannschaft.app.todo.entity.TodoAssigneeEntity;
import com.mannschaft.app.todo.entity.TodoEntity;
import com.mannschaft.app.todo.event.TodoStatusChangedEvent;
import com.mannschaft.app.todo.exception.MilestoneLockedException;
import com.mannschaft.app.todo.repository.ProjectRepository;
import com.mannschaft.app.todo.repository.ProjectMilestoneRepository;
import com.mannschaft.app.todo.repository.TodoAssigneeRepository;
import com.mannschaft.app.todo.repository.TodoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * TODOサービス。TODOのCRUD・ステータス管理・担当者割り当てを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TodoService {

    private static final int MAX_BULK_SIZE = 50;
    private static final int MAX_CHILD_SIZE = 50;

    private final TodoRepository todoRepository;
    private final TodoAssigneeRepository assigneeRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMilestoneRepository milestoneRepository;
    private final ProjectService projectService;
    private final NameResolverService nameResolverService;
    private final ApplicationEventPublisher eventPublisher;
    private final TodoProgressService todoProgressService;
    private final MilestoneGateService milestoneGateService;

    /**
     * TODO一覧を取得する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param status    ステータスフィルタ（NULLで全件）
     * @param page      ページ番号（1始まり）
     * @param size      ページサイズ
     * @return TODO一覧
     */
    public PagedResponse<TodoResponse> listTodos(TodoScopeType scopeType, Long scopeId,
                                                  TodoStatus status, int page, int size) {
        PageRequest pageable = PageRequest.of(page - 1, size,
                Sort.by("priority").descending().and(Sort.by("dueDate").ascending()));

        Page<TodoEntity> pageResult;
        if (status != null) {
            pageResult = todoRepository.findByScopeTypeAndScopeIdAndStatusAndDeletedAtIsNull(
                    scopeType, scopeId, status, pageable);
        } else {
            pageResult = todoRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNull(
                    scopeType, scopeId, pageable);
        }

        List<TodoResponse> responses = pageResult.getContent().stream()
                .map(this::toTodoResponse)
                .toList();

        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                pageResult.getTotalElements(), page, size, pageResult.getTotalPages());
        return PagedResponse.of(responses, meta);
    }

    /**
     * プロジェクト内のTODO一覧を取得する。
     *
     * @param projectId プロジェクトID
     * @return TODO一覧
     */
    public ApiResponse<List<TodoResponse>> listProjectTodos(Long projectId) {
        projectService.findProjectOrThrow(projectId);
        List<TodoResponse> responses = todoRepository
                .findByProjectIdAndDeletedAtIsNullOrderBySortOrderAsc(projectId).stream()
                .map(this::toTodoResponse)
                .toList();
        return ApiResponse.of(responses);
    }

    /**
     * TODO詳細を取得する。
     *
     * @param todoId Todo ID
     * @return TODO詳細
     */
    public ApiResponse<TodoResponse> getTodo(Long todoId) {
        TodoEntity todo = findTodoOrThrow(todoId);
        return ApiResponse.of(toTodoResponseWithStats(todo));
    }

    /**
     * TODOを作成する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param request   作成リクエスト
     * @param userId    作成者ID
     * @return 作成されたTODO
     */
    @Transactional
    public ApiResponse<TodoResponse> createTodo(TodoScopeType scopeType, Long scopeId,
                                                 CreateTodoRequest request, Long userId) {
        // 親TODO処理
        Integer depth = 0;
        Long parentId = request.getParentId();
        if (parentId != null) {
            // IDOR対策: スコープフィルタ付きで検索し、他スコープのID存在を推測させない
            TodoEntity parent = todoRepository.findByIdAndDeletedAtIsNull(parentId)
                    .filter(p -> p.getScopeType() == scopeType && p.getScopeId().equals(scopeId))
                    .orElseThrow(() -> new BusinessException(TodoErrorCode.TODO_NOT_FOUND));

            // 深度チェック（最大3階層: depth 0,1,2）
            if (parent.getDepth() >= 2) {
                throw new BusinessException(TodoErrorCode.MAX_DEPTH_EXCEEDED);
            }

            // プロジェクト一致チェック
            if (!java.util.Objects.equals(parent.getProjectId(), request.getProjectId())) {
                throw new BusinessException(TodoErrorCode.SCOPE_MISMATCH);
            }

            // 子TODO上限チェック
            long childCount = todoRepository.countByParentIdAndDeletedAtIsNull(parentId);
            if (childCount >= MAX_CHILD_SIZE) {
                throw new BusinessException(TodoErrorCode.CHILD_LIMIT_EXCEEDED);
            }

            depth = parent.getDepth() + 1;
        }

        // プロジェクト整合性チェック
        Long projectId = request.getProjectId();
        if (projectId != null) {
            ProjectEntity project = projectService.findProjectOrThrow(projectId);
            if (project.getScopeType() != scopeType || !project.getScopeId().equals(scopeId)) {
                throw new BusinessException(TodoErrorCode.SCOPE_MISMATCH);
            }
        }

        // マイルストーン整合性チェック
        Long milestoneId = request.getMilestoneId();
        if (milestoneId != null) {
            if (projectId == null) {
                throw new BusinessException(TodoErrorCode.MILESTONE_REQUIRES_PROJECT);
            }
            milestoneRepository.findByIdAndProjectId(milestoneId, projectId)
                    .orElseThrow(() -> new BusinessException(TodoErrorCode.MILESTONE_NOT_IN_PROJECT));
        }

        // 開始日 ≤ 期限日チェック
        if (request.getStartDate() != null && request.getDueDate() != null
                && request.getStartDate().isAfter(request.getDueDate())) {
            throw new BusinessException(TodoErrorCode.START_DATE_AFTER_DUE_DATE);
        }

        TodoPriority priority = request.getPriority() != null
                ? TodoPriority.valueOf(request.getPriority())
                : TodoPriority.MEDIUM;

        TodoEntity todo = TodoEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .projectId(projectId)
                .milestoneId(milestoneId)
                .title(request.getTitle())
                .description(request.getDescription())
                .priority(priority)
                .dueDate(request.getDueDate())
                .dueTime(request.getDueTime())
                .startDate(request.getStartDate())
                .sortOrder(request.getSortOrder() != null ? request.getSortOrder() : 0)
                .createdBy(userId)
                .parentId(parentId)
                .depth(depth)
                .build();

        todo = todoRepository.save(todo);

        // 担当者割り当て
        if (request.getAssigneeIds() != null && !request.getAssigneeIds().isEmpty()) {
            for (Long assigneeId : request.getAssigneeIds()) {
                TodoAssigneeEntity assignee = TodoAssigneeEntity.builder()
                        .todoId(todo.getId())
                        .userId(assigneeId)
                        .assignedBy(userId)
                        .build();
                assigneeRepository.save(assignee);
            }
        }

        // プロジェクト進捗再計算
        if (projectId != null) {
            projectRepository.recalculateProgress(projectId);
        }

        // 親TODO（自動モード）の進捗率再計算
        if (parentId != null) {
            todoProgressService.recalculateAfterChildChange(parentId);
        }

        log.info("TODO作成: id={}, title={}, scope={}:{}", todo.getId(), todo.getTitle(), scopeType, scopeId);
        return ApiResponse.of(toTodoResponse(todo));
    }

    /**
     * TODOを更新する。
     *
     * @param todoId  Todo ID
     * @param request 更新リクエスト
     * @return 更新されたTODO
     */
    @Transactional
    public ApiResponse<TodoResponse> updateTodo(Long todoId, UpdateTodoRequest request) {
        TodoEntity todo = findTodoOrThrow(todoId);
        // F02.7: ロック中 TODO の編集（タイトル・説明・期限・優先度）は 423 Locked
        assertNotMilestoneLocked(todo);

        Long oldProjectId = todo.getProjectId();
        Long newProjectId = request.getProjectId();

        // 子TODOがある場合はプロジェクト変更を拒否
        if (!java.util.Objects.equals(todo.getProjectId(), request.getProjectId())) {
            long childCount = todoRepository.countByParentIdAndDeletedAtIsNull(todoId);
            if (childCount > 0) {
                throw new BusinessException(TodoErrorCode.SCOPE_MISMATCH);
            }
        }

        // プロジェクト変更時の整合性チェック
        if (newProjectId != null) {
            ProjectEntity newProject = projectService.findProjectOrThrow(newProjectId);
            if (newProject.getScopeType() != todo.getScopeType() || !newProject.getScopeId().equals(todo.getScopeId())) {
                throw new BusinessException(TodoErrorCode.SCOPE_MISMATCH);
            }
        }

        // マイルストーン整合性チェック
        Long milestoneId = request.getMilestoneId();
        if (milestoneId != null) {
            Long effectiveProjectId = newProjectId != null ? newProjectId : todo.getProjectId();
            if (effectiveProjectId == null) {
                throw new BusinessException(TodoErrorCode.MILESTONE_REQUIRES_PROJECT);
            }
            milestoneRepository.findByIdAndProjectId(milestoneId, effectiveProjectId)
                    .orElseThrow(() -> new BusinessException(TodoErrorCode.MILESTONE_NOT_IN_PROJECT));
        }

        TodoPriority priority = request.getPriority() != null
                ? TodoPriority.valueOf(request.getPriority())
                : todo.getPriority();

        // プロジェクト間移動の場合、milestoneIdをリセット
        boolean projectChanged = !java.util.Objects.equals(oldProjectId, newProjectId);
        Long effectiveMilestoneId = projectChanged ? null : (milestoneId != null ? milestoneId : todo.getMilestoneId());

        todo = todo.toBuilder()
                .title(request.getTitle())
                .description(request.getDescription())
                .projectId(newProjectId)
                .milestoneId(effectiveMilestoneId)
                .priority(priority)
                .dueDate(request.getDueDate())
                .dueTime(request.getDueTime())
                .sortOrder(request.getSortOrder() != null ? request.getSortOrder() : todo.getSortOrder())
                .build();

        todo = todoRepository.save(todo);

        // プロジェクト進捗再計算（旧・新プロジェクト両方）
        if (projectChanged) {
            if (oldProjectId != null) {
                projectRepository.recalculateProgress(oldProjectId);
            }
            if (newProjectId != null) {
                projectRepository.recalculateProgress(newProjectId);
            }
        }

        return ApiResponse.of(toTodoResponse(todo));
    }

    /**
     * TODOを論理削除する。
     *
     * @param todoId Todo ID
     */
    @Transactional
    public void deleteTodo(Long todoId) {
        TodoEntity todo = findTodoOrThrow(todoId);
        Long parentId = todo.getParentId();
        todo.softDelete();
        todoRepository.save(todo);

        // プロジェクト進捗再計算
        if (todo.getProjectId() != null) {
            projectRepository.recalculateProgress(todo.getProjectId());
        }

        // 親TODO（自動モード）の進捗率再計算
        if (parentId != null) {
            todoProgressService.recalculateAfterChildChange(parentId);
        }

        log.info("TODO削除: id={}", todoId);
    }

    /**
     * TODOステータスを変更する。
     *
     * @param todoId  Todo ID
     * @param request ステータス変更リクエスト
     * @param userId  操作ユーザーID
     * @return ステータス変更レスポンス
     */
    @Transactional
    public ApiResponse<TodoStatusChangeResponse> changeStatus(Long todoId,
                                                               TodoStatusChangeRequest request, Long userId) {
        TodoEntity todo = findTodoOrThrow(todoId);
        // F02.7: ロック中 TODO のステータス変更は 423 Locked
        assertNotMilestoneLocked(todo);

        TodoStatus oldStatus = todo.getStatus();
        TodoStatus newStatus = TodoStatus.valueOf(request.getStatus());

        todo.changeStatus(newStatus, userId);
        todo = todoRepository.save(todo);

        // プロジェクト進捗再計算
        TodoStatusChangeResponse.ProjectProgress projectProgress = null;
        if (todo.getProjectId() != null) {
            projectRepository.recalculateProgress(todo.getProjectId());
            ProjectEntity project = projectService.findProjectOrThrow(todo.getProjectId());
            projectProgress = new TodoStatusChangeResponse.ProjectProgress(
                    project.getId(), project.getProgressRate(),
                    project.getTotalTodos(), project.getCompletedTodos());
        }

        // イベント発行
        eventPublisher.publishEvent(new TodoStatusChangedEvent(
                todoId, todo.getProjectId(), oldStatus, newStatus, userId));

        // COMPLETED遷移後の進捗率再計算（自動モード）
        todoProgressService.recalculateAncestors(todo);

        // F02.7: マイルストーン進捗・自動完了・後続アンロックを評価
        if (todo.getMilestoneId() != null) {
            milestoneGateService.evaluateOnTodoStatusChanged(todoId, newStatus);
        }

        ProjectResponse.UserInfo completedByInfo = null;
        if (todo.getCompletedBy() != null) {
            Map<Long, String> nameMap = nameResolverService.resolveUserDisplayNames(Set.of(todo.getCompletedBy()));
            completedByInfo = new ProjectResponse.UserInfo(todo.getCompletedBy(), nameMap.getOrDefault(todo.getCompletedBy(), ""));
        }

        TodoStatusChangeResponse response = new TodoStatusChangeResponse(
                todo.getId(), todo.getStatus().name(), todo.getCompletedAt(),
                completedByInfo, projectProgress);

        return ApiResponse.of(response);
    }

    /**
     * TODO一括ステータス変更。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param request   一括ステータス変更リクエスト
     * @param userId    操作ユーザーID
     * @return 変更結果リスト
     */
    @Transactional
    public ApiResponse<List<TodoStatusChangeResponse>> bulkChangeStatus(
            TodoScopeType scopeType, Long scopeId, BulkStatusChangeRequest request, Long userId) {
        if (request.getTodoIds().size() > MAX_BULK_SIZE) {
            throw new BusinessException(TodoErrorCode.BULK_SIZE_EXCEEDED);
        }

        TodoStatus newStatus = TodoStatus.valueOf(request.getStatus());
        List<TodoEntity> todos = todoRepository.findByIdInAndDeletedAtIsNull(request.getTodoIds());

        // F02.7: ロック中 TODO をスキップする（DTO 拡張（skipped_locked_ids）は Phase 15-3 で実装予定）
        List<Long> skippedLockedIds = new java.util.ArrayList<>();
        List<TodoEntity> processable = new java.util.ArrayList<>();
        for (TodoEntity t : todos) {
            if (Boolean.TRUE.equals(t.getMilestoneLocked())) {
                skippedLockedIds.add(t.getId());
            } else {
                processable.add(t);
            }
        }
        if (!skippedLockedIds.isEmpty()) {
            log.warn("bulkChangeStatus: ロック中TODOをスキップ skippedIds={}", skippedLockedIds);
        }

        List<TodoStatusChangeResponse> responses = processable.stream().map(todo -> {
            TodoStatus oldStatus = todo.getStatus();
            todo.changeStatus(newStatus, userId);
            todoRepository.save(todo);

            TodoStatusChangeResponse.ProjectProgress projectProgress = null;
            if (todo.getProjectId() != null) {
                projectRepository.recalculateProgress(todo.getProjectId());
                ProjectEntity project = projectService.findProjectOrThrow(todo.getProjectId());
                projectProgress = new TodoStatusChangeResponse.ProjectProgress(
                        project.getId(), project.getProgressRate(),
                        project.getTotalTodos(), project.getCompletedTodos());
            }

            eventPublisher.publishEvent(new TodoStatusChangedEvent(
                    todo.getId(), todo.getProjectId(), oldStatus, newStatus, userId));

            // F02.7: マイルストーン進捗・自動完了・後続アンロックを評価
            if (todo.getMilestoneId() != null) {
                milestoneGateService.evaluateOnTodoStatusChanged(todo.getId(), newStatus);
            }

            ProjectResponse.UserInfo completedByInfo = null;
            if (todo.getCompletedBy() != null) {
                Map<Long, String> nm = nameResolverService.resolveUserDisplayNames(Set.of(todo.getCompletedBy()));
                completedByInfo = new ProjectResponse.UserInfo(todo.getCompletedBy(), nm.getOrDefault(todo.getCompletedBy(), ""));
            }

            return new TodoStatusChangeResponse(
                    todo.getId(), todo.getStatus().name(), todo.getCompletedAt(),
                    completedByInfo, projectProgress);
        }).toList();

        return ApiResponse.of(responses);
    }

    /**
     * TODOを部分更新する（PATCH）。
     * 個人TODOの担当者本人のみ更新可能。IDOR対策としてTODO_NOT_FOUNDで統一する。
     *
     * @param todoId  Todo ID
     * @param userId  操作ユーザーID
     * @param request 部分更新リクエスト
     * @return 更新されたTODO
     */
    @Transactional
    public ApiResponse<TodoResponse> patchTodo(Long todoId, Long userId, PatchTodoRequest request) {
        TodoEntity todo = todoRepository.findByIdAndDeletedAtIsNull(todoId)
                .orElseThrow(() -> new BusinessException(TodoErrorCode.TODO_NOT_FOUND));

        // 担当者であることを検証（IDOR対策: 他人のTODOはNOT_FOUNDで返す）
        boolean isAssignee = assigneeRepository.existsByTodoIdAndUserId(todoId, userId);
        if (!isAssignee) {
            throw new BusinessException(TodoErrorCode.TODO_NOT_FOUND);
        }

        // dueDate の部分更新
        if (request.getDueDate() != null) {
            todo.updateDueDate(request.getDueDate());
        }

        todo = todoRepository.save(todo);

        log.info("TODO部分更新: id={}, userId={}", todoId, userId);
        return ApiResponse.of(toTodoResponse(todo));
    }

    /**
     * 自分に割り当てられた全TODOを取得する。
     *
     * @param userId ユーザーID
     * @return 自分のTODO一覧
     */
    public ApiResponse<List<TodoResponse>> getMyTodos(Long userId) {
        List<TodoResponse> responses = todoRepository.findMyTodos(userId).stream()
                .map(this::toTodoResponse)
                .toList();
        return ApiResponse.of(responses);
    }

    /**
     * 指定TODOの直接の子TODO一覧を取得する。スコープ認可チェック付き。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param todoId    親TODO ID
     * @return 子TODO一覧
     */
    public ApiResponse<List<TodoResponse>> getChildTodos(
            TodoScopeType scopeType, Long scopeId, Long todoId) {
        // スコープ認可: 他スコープのIDを推測させないため TODO_NOT_FOUND で統一
        TodoEntity parent = todoRepository.findByIdAndDeletedAtIsNull(todoId)
                .filter(p -> p.getScopeType() == scopeType && p.getScopeId().equals(scopeId))
                .orElseThrow(() -> new BusinessException(TodoErrorCode.TODO_NOT_FOUND));

        List<TodoEntity> children = todoRepository
                .findByParentIdAndDeletedAtIsNullOrderBySortOrderAsc(parent.getId());
        return ApiResponse.of(children.stream().map(this::toTodoResponse).toList());
    }

    // --- 進捗率管理 ---

    /**
     * 進捗率を手動設定する（手動モード必須）。
     * 自動算出モードのTODOには設定不可（TODO_040エラー）。
     *
     * @param todoId      Todo ID
     * @param progressRate 設定する進捗率（0.00〜100.00）
     * @return 更新されたTODO
     */
    @Transactional
    public ApiResponse<TodoResponse> setProgressRate(Long todoId, java.math.BigDecimal progressRate) {
        TodoEntity todo = findTodoOrThrow(todoId);

        // 自動算出モードのTODOには設定不可
        if (Boolean.FALSE.equals(todo.getProgressManual())) {
            throw new BusinessException(TodoErrorCode.AUTO_PROGRESS_MODE);
        }

        todoProgressService.setManualProgressRate(todo, progressRate);

        // 更新後のエンティティを再取得
        TodoEntity updated = findTodoOrThrow(todoId);
        return ApiResponse.of(toTodoResponse(updated));
    }

    /**
     * 進捗モードを切り替える（手動 ↔ 自動）。
     *
     * @param todoId         Todo ID
     * @param progressManual true: 手動モード / false: 自動算出モード
     * @return 更新されたTODO
     */
    @Transactional
    public ApiResponse<TodoResponse> setProgressMode(Long todoId, boolean progressManual) {
        TodoEntity todo = findTodoOrThrow(todoId);

        if (progressManual) {
            // 手動モードへ切替（現在の進捗率はそのまま維持）
            TodoEntity updated = todo.toBuilder()
                    .progressManual(true)
                    .build();
            todoRepository.save(updated);
            return ApiResponse.of(toTodoResponse(updated));
        } else {
            // 自動算出モードへ切替（子の平均から再計算）
            todoProgressService.switchToAutoMode(todo);
            TodoEntity updated = findTodoOrThrow(todoId);
            return ApiResponse.of(toTodoResponse(updated));
        }
    }

    // --- 担当者管理 ---

    /**
     * 担当者を追加する。
     *
     * @param todoId  Todo ID
     * @param request 担当者追加リクエスト
     * @param userId  操作ユーザーID
     * @return 追加された担当者
     */
    @Transactional
    public ApiResponse<AssigneeResponse> addAssignee(Long todoId, AddAssigneeRequest request, Long userId) {
        TodoEntity todo = findTodoOrThrow(todoId);
        // F02.7: ロック中 TODO の担当者変更は 423 Locked
        assertNotMilestoneLocked(todo);

        if (assigneeRepository.existsByTodoIdAndUserId(todoId, request.getUserId())) {
            throw new BusinessException(TodoErrorCode.ASSIGNEE_ALREADY_EXISTS);
        }

        TodoAssigneeEntity assignee = TodoAssigneeEntity.builder()
                .todoId(todoId)
                .userId(request.getUserId())
                .assignedBy(userId)
                .build();

        assignee = assigneeRepository.save(assignee);
        return ApiResponse.of(toAssigneeResponse(assignee));
    }

    /**
     * 担当者を削除する。
     *
     * @param todoId Todo ID
     * @param targetUserId 削除対象のユーザーID
     */
    @Transactional
    public void removeAssignee(Long todoId, Long targetUserId) {
        TodoEntity todo = findTodoOrThrow(todoId);
        // F02.7: ロック中 TODO の担当者変更は 423 Locked
        assertNotMilestoneLocked(todo);

        TodoAssigneeEntity assignee = assigneeRepository.findByTodoIdAndUserId(todoId, targetUserId)
                .orElseThrow(() -> new BusinessException(TodoErrorCode.ASSIGNEE_NOT_FOUND));
        assigneeRepository.delete(assignee);
    }

    // --- プライベートメソッド ---

    /**
     * TODOを取得する。存在しない場合は例外をスローする。
     */
    private TodoEntity findTodoOrThrow(Long todoId) {
        return todoRepository.findByIdAndDeletedAtIsNull(todoId)
                .orElseThrow(() -> new BusinessException(TodoErrorCode.TODO_NOT_FOUND));
    }

    /**
     * マイルストーンロック中 TODO に対する操作を拒否する（F02.7）。
     *
     * <p>論理削除は分母を減らすだけで達成判定を不当に早めないため例外許可（呼び出し側で
     * 本メソッドを呼ばないことで実現）。本メソッドはステータス変更・編集・担当者変更で使用する。</p>
     *
     * @param todo 対象 TODO
     * @throws MilestoneLockedException ロック中の場合
     */
    private void assertNotMilestoneLocked(TodoEntity todo) {
        if (!Boolean.TRUE.equals(todo.getMilestoneLocked())) {
            return;
        }
        Long milestoneId = todo.getMilestoneId();
        String blockingTitle = "";
        if (milestoneId != null) {
            ProjectMilestoneEntity milestone = milestoneRepository.findById(milestoneId).orElse(null);
            if (milestone != null && milestone.getLockedByMilestoneId() != null) {
                blockingTitle = milestoneRepository.findById(milestone.getLockedByMilestoneId())
                        .map(ProjectMilestoneEntity::getTitle).orElse("");
            }
        }
        throw new MilestoneLockedException(milestoneId, blockingTitle);
    }

    /**
     * 残日数を算出する。
     */
    private Long calculateDaysRemaining(LocalDate dueDate) {
        if (dueDate == null) {
            return null;
        }
        return ChronoUnit.DAYS.between(LocalDate.now(), dueDate);
    }

    /**
     * エンティティをレスポンスDTOに変換する（一覧用、N+1防止のため統計なし）。
     */
    private TodoResponse toTodoResponse(TodoEntity entity) {
        List<TodoAssigneeEntity> assigneeEntities = assigneeRepository.findByTodoId(entity.getId());

        // 関連ユーザーIDを一括収集して名前解決
        Set<Long> userIds = Stream.concat(
                Stream.of(entity.getCreatedBy(), entity.getCompletedBy()),
                assigneeEntities.stream().map(TodoAssigneeEntity::getUserId)
        ).filter(id -> id != null).collect(Collectors.toSet());
        Map<Long, String> nameMap = nameResolverService.resolveUserDisplayNames(userIds);

        List<AssigneeResponse> assignees = assigneeEntities.stream()
                .map(a -> new AssigneeResponse(
                        a.getId(), a.getUserId(), nameMap.getOrDefault(a.getUserId(), ""),
                        a.getAssignedBy(), a.getCreatedAt()))
                .toList();

        ProjectResponse.UserInfo completedByInfo = entity.getCompletedBy() != null
                ? new ProjectResponse.UserInfo(entity.getCompletedBy(), nameMap.getOrDefault(entity.getCompletedBy(), ""))
                : null;

        return new TodoResponse(
                entity.getId(), entity.getScopeType().name(), entity.getScopeId(),
                entity.getProjectId(), entity.getMilestoneId(),
                entity.getTitle(), entity.getDescription(),
                entity.getStatus().name(), entity.getPriority().name(),
                entity.getDueDate(), entity.getDueTime(),
                calculateDaysRemaining(entity.getDueDate()),
                entity.getCompletedAt(), completedByInfo,
                new ProjectResponse.UserInfo(entity.getCreatedBy(), nameMap.getOrDefault(entity.getCreatedBy(), "")),
                entity.getSortOrder(), assignees,
                entity.getCreatedAt(), entity.getUpdatedAt(),
                // 親子情報
                entity.getParentId(), entity.getDepth(),
                java.util.List.of(), 0, 0, 0,  // 一覧では統計なし
                // Phase 2 フィールド
                entity.getStartDate(), entity.getLinkedScheduleId(),
                entity.getProgressRate(), entity.getProgressManual());
    }

    /**
     * エンティティをレスポンスDTOに変換する（詳細用、子TODO統計含む）。
     */
    private TodoResponse toTodoResponseWithStats(TodoEntity entity) {
        long childCount = todoRepository.countByParentIdAndDeletedAtIsNull(entity.getId());
        long descendantTotal = todoRepository.countDescendants(entity.getId());
        long descendantCompleted = todoRepository.countCompletedDescendants(entity.getId());
        List<TodoEntity> childEntities = todoRepository
                .findByParentIdAndDeletedAtIsNullOrderBySortOrderAsc(entity.getId());
        List<TodoResponse> children = childEntities.stream()
                .map(this::toTodoResponse)
                .toList();

        List<TodoAssigneeEntity> assigneeEntities = assigneeRepository.findByTodoId(entity.getId());
        Set<Long> userIds = Stream.concat(
                Stream.of(entity.getCreatedBy(), entity.getCompletedBy()),
                assigneeEntities.stream().map(TodoAssigneeEntity::getUserId)
        ).filter(id -> id != null).collect(Collectors.toSet());
        Map<Long, String> nameMap = nameResolverService.resolveUserDisplayNames(userIds);

        List<AssigneeResponse> assignees = assigneeEntities.stream()
                .map(a -> new AssigneeResponse(
                        a.getId(), a.getUserId(), nameMap.getOrDefault(a.getUserId(), ""),
                        a.getAssignedBy(), a.getCreatedAt()))
                .toList();

        ProjectResponse.UserInfo completedByInfo = entity.getCompletedBy() != null
                ? new ProjectResponse.UserInfo(entity.getCompletedBy(), nameMap.getOrDefault(entity.getCompletedBy(), ""))
                : null;

        return new TodoResponse(
                entity.getId(), entity.getScopeType().name(), entity.getScopeId(),
                entity.getProjectId(), entity.getMilestoneId(),
                entity.getTitle(), entity.getDescription(),
                entity.getStatus().name(), entity.getPriority().name(),
                entity.getDueDate(), entity.getDueTime(),
                calculateDaysRemaining(entity.getDueDate()),
                entity.getCompletedAt(), completedByInfo,
                new ProjectResponse.UserInfo(entity.getCreatedBy(), nameMap.getOrDefault(entity.getCreatedBy(), "")),
                entity.getSortOrder(), assignees,
                entity.getCreatedAt(), entity.getUpdatedAt(),
                entity.getParentId(), entity.getDepth(),
                children, (int) childCount,
                (int) descendantCompleted, (int) descendantTotal,
                // Phase 2 フィールド
                entity.getStartDate(), entity.getLinkedScheduleId(),
                entity.getProgressRate(), entity.getProgressManual());
    }

    /**
     * 担当者エンティティをレスポンスDTOに変換する。
     */
    private AssigneeResponse toAssigneeResponse(TodoAssigneeEntity entity) {
        Map<Long, String> nameMap = nameResolverService.resolveUserDisplayNames(Set.of(entity.getUserId()));
        return new AssigneeResponse(
                entity.getId(), entity.getUserId(), nameMap.getOrDefault(entity.getUserId(), ""),
                entity.getAssignedBy(), entity.getCreatedAt());
    }
}
