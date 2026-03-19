package com.mannschaft.app.todo;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.todo.dto.AddAssigneeRequest;
import com.mannschaft.app.todo.dto.AssigneeResponse;
import com.mannschaft.app.todo.dto.BulkStatusChangeRequest;
import com.mannschaft.app.todo.dto.CreateTodoRequest;
import com.mannschaft.app.todo.dto.ProjectResponse;
import com.mannschaft.app.todo.dto.TodoResponse;
import com.mannschaft.app.todo.dto.TodoStatusChangeRequest;
import com.mannschaft.app.todo.dto.TodoStatusChangeResponse;
import com.mannschaft.app.todo.dto.UpdateTodoRequest;
import com.mannschaft.app.todo.event.TodoStatusChangedEvent;
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

/**
 * TODOサービス。TODOのCRUD・ステータス管理・担当者割り当てを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TodoService {

    private static final int MAX_BULK_SIZE = 50;

    private final TodoRepository todoRepository;
    private final TodoAssigneeRepository assigneeRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMilestoneRepository milestoneRepository;
    private final ProjectService projectService;
    private final ApplicationEventPublisher eventPublisher;

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
     * @param todoId TODO ID
     * @return TODO詳細
     */
    public ApiResponse<TodoResponse> getTodo(Long todoId) {
        TodoEntity todo = findTodoOrThrow(todoId);
        return ApiResponse.of(toTodoResponse(todo));
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
                .sortOrder(request.getSortOrder() != null ? request.getSortOrder() : 0)
                .createdBy(userId)
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

        log.info("TODO作成: id={}, title={}, scope={}:{}", todo.getId(), todo.getTitle(), scopeType, scopeId);
        return ApiResponse.of(toTodoResponse(todo));
    }

    /**
     * TODOを更新する。
     *
     * @param todoId  TODO ID
     * @param request 更新リクエスト
     * @return 更新されたTODO
     */
    @Transactional
    public ApiResponse<TodoResponse> updateTodo(Long todoId, UpdateTodoRequest request) {
        TodoEntity todo = findTodoOrThrow(todoId);
        Long oldProjectId = todo.getProjectId();
        Long newProjectId = request.getProjectId();

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
     * @param todoId TODO ID
     */
    @Transactional
    public void deleteTodo(Long todoId) {
        TodoEntity todo = findTodoOrThrow(todoId);
        todo.softDelete();
        todoRepository.save(todo);

        // プロジェクト進捗再計算
        if (todo.getProjectId() != null) {
            projectRepository.recalculateProgress(todo.getProjectId());
        }

        log.info("TODO削除: id={}", todoId);
    }

    /**
     * TODOステータスを変更する。
     *
     * @param todoId  TODO ID
     * @param request ステータス変更リクエスト
     * @param userId  操作ユーザーID
     * @return ステータス変更レスポンス
     */
    @Transactional
    public ApiResponse<TodoStatusChangeResponse> changeStatus(Long todoId,
                                                               TodoStatusChangeRequest request, Long userId) {
        TodoEntity todo = findTodoOrThrow(todoId);
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

        ProjectResponse.UserInfo completedByInfo = todo.getCompletedBy() != null
                ? new ProjectResponse.UserInfo(todo.getCompletedBy(), "TODO:表示名取得")
                : null;

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

        List<TodoStatusChangeResponse> responses = todos.stream().map(todo -> {
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

            ProjectResponse.UserInfo completedByInfo = todo.getCompletedBy() != null
                    ? new ProjectResponse.UserInfo(todo.getCompletedBy(), "TODO:表示名取得")
                    : null;

            return new TodoStatusChangeResponse(
                    todo.getId(), todo.getStatus().name(), todo.getCompletedAt(),
                    completedByInfo, projectProgress);
        }).toList();

        return ApiResponse.of(responses);
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

    // --- 担当者管理 ---

    /**
     * 担当者を追加する。
     *
     * @param todoId  TODO ID
     * @param request 担当者追加リクエスト
     * @param userId  操作ユーザーID
     * @return 追加された担当者
     */
    @Transactional
    public ApiResponse<AssigneeResponse> addAssignee(Long todoId, AddAssigneeRequest request, Long userId) {
        findTodoOrThrow(todoId);

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
     * @param todoId TODO ID
     * @param targetUserId 削除対象のユーザーID
     */
    @Transactional
    public void removeAssignee(Long todoId, Long targetUserId) {
        findTodoOrThrow(todoId);
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
     * 残日数を算出する。
     */
    private Long calculateDaysRemaining(LocalDate dueDate) {
        if (dueDate == null) {
            return null;
        }
        return ChronoUnit.DAYS.between(LocalDate.now(), dueDate);
    }

    /**
     * エンティティをレスポンスDTOに変換する。
     */
    private TodoResponse toTodoResponse(TodoEntity entity) {
        List<AssigneeResponse> assignees = assigneeRepository.findByTodoId(entity.getId()).stream()
                .map(this::toAssigneeResponse)
                .toList();

        ProjectResponse.UserInfo completedByInfo = entity.getCompletedBy() != null
                ? new ProjectResponse.UserInfo(entity.getCompletedBy(), "TODO:表示名取得")
                : null;

        return new TodoResponse(
                entity.getId(), entity.getScopeType().name(), entity.getScopeId(),
                entity.getProjectId(), entity.getMilestoneId(),
                entity.getTitle(), entity.getDescription(),
                entity.getStatus().name(), entity.getPriority().name(),
                entity.getDueDate(), entity.getDueTime(),
                calculateDaysRemaining(entity.getDueDate()),
                entity.getCompletedAt(), completedByInfo,
                new ProjectResponse.UserInfo(entity.getCreatedBy(), "TODO:表示名取得"),
                entity.getSortOrder(), assignees,
                entity.getCreatedAt(), entity.getUpdatedAt());
    }

    /**
     * 担当者エンティティをレスポンスDTOに変換する。
     */
    private AssigneeResponse toAssigneeResponse(TodoAssigneeEntity entity) {
        return new AssigneeResponse(
                entity.getId(), entity.getUserId(), "TODO:表示名取得",
                entity.getAssignedBy(), entity.getCreatedAt());
    }
}
