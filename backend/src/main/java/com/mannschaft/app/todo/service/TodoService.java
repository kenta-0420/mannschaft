package com.mannschaft.app.todo.service;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.todo.TodoErrorCode;
import com.mannschaft.app.todo.TodoPriority;
import com.mannschaft.app.todo.TodoScopeType;
import com.mannschaft.app.todo.TodoStatus;
import com.mannschaft.app.todo.dto.CreateTodoRequest;
import com.mannschaft.app.todo.dto.PatchTodoRequest;
import com.mannschaft.app.todo.dto.TodoResponse;
import com.mannschaft.app.todo.dto.UpdateTodoRequest;
import com.mannschaft.app.todo.entity.ProjectEntity;
import com.mannschaft.app.todo.entity.ProjectMilestoneEntity;
import com.mannschaft.app.todo.entity.TodoAssigneeEntity;
import com.mannschaft.app.todo.entity.TodoEntity;
import com.mannschaft.app.todo.exception.MilestoneLockedException;
import com.mannschaft.app.todo.repository.ProjectMilestoneRepository;
import com.mannschaft.app.todo.repository.ProjectRepository;
import com.mannschaft.app.todo.repository.TodoAssigneeRepository;
import com.mannschaft.app.todo.repository.TodoRepository;
import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * TODOサービス。TODOのCRUD・取得・進捗管理を担当する。
 * ステータス遷移は {@link TodoStatusService}、担当者管理は {@link TodoAssigneeService}、
 * DTO変換は {@link TodoResponseConverter} が担当する。
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
    private final TodoProgressService todoProgressService;
    private final TodoResponseConverter responseConverter;

    /**
     * TODO一覧を取得する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param status    ステータスフィルタ（NULLで全件）
     * @param page      ページ番号（0始まり）
     * @param size      ページサイズ
     * @param sortType  ソート種別。"PRIORITY" = 優先度降順→期限昇順→作成日降順、
     *                  それ以外（"RECENT" または未指定）= 作成日降順（新着順）。
     * @return TODO一覧
     */
    @Timed(value = "mannschaft.repository.query", extraTags = {"operation", "TodoService.listTodos"})
    public PagedResponse<TodoResponse> listTodos(TodoScopeType scopeType, Long scopeId,
                                                  TodoStatus status, int page, int size,
                                                  String sortType) {
        Sort sort = buildSort(sortType);
        PageRequest pageable = PageRequest.of(page, size, sort);

        Page<TodoEntity> pageResult;
        if (status != null) {
            pageResult = todoRepository.findByScopeTypeAndScopeIdAndStatusAndDeletedAtIsNull(
                    scopeType, scopeId, status, pageable);
        } else {
            pageResult = todoRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNull(
                    scopeType, scopeId, pageable);
        }

        List<TodoResponse> responses = responseConverter.toTodoResponseList(pageResult.getContent());

        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                pageResult.getTotalElements(), pageResult.getNumber(), pageResult.getSize(), pageResult.getTotalPages());
        return PagedResponse.of(responses, meta);
    }

    /**
     * ソート種別文字列から {@link Sort} を構築する。
     *
     * <ul>
     *   <li>{@code "PRIORITY"} — 優先度降順 → 期限昇順 → 作成日降順</li>
     *   <li>それ以外（{@code "RECENT"} / {@code null} / 不正値）— 作成日降順（新着順・既定）</li>
     * </ul>
     */
    private Sort buildSort(String sortType) {
        if ("PRIORITY".equals(sortType)) {
            return Sort.by(Sort.Order.desc("priority"))
                    .and(Sort.by(Sort.Order.asc("dueDate")))
                    .and(Sort.by(Sort.Order.desc("createdAt")));
        }
        // RECENT（既定）: 作成新着順
        return Sort.by(Sort.Order.desc("createdAt"));
    }

    /**
     * プロジェクト内のTODO一覧を取得する。
     *
     * @param projectId プロジェクトID
     * @return TODO一覧
     */
    public ApiResponse<List<TodoResponse>> listProjectTodos(Long projectId) {
        projectService.findProjectOrThrow(projectId);
        List<TodoEntity> entities = todoRepository
                .findByProjectIdAndDeletedAtIsNullOrderBySortOrderAsc(projectId);
        return ApiResponse.of(responseConverter.toTodoResponseList(entities));
    }

    /**
     * TODO詳細を取得する。
     *
     * @param todoId Todo ID
     * @return TODO詳細
     */
    public ApiResponse<TodoResponse> getTodo(Long todoId) {
        TodoEntity todo = findTodoOrThrow(todoId);
        return ApiResponse.of(responseConverter.toTodoResponseWithStats(todo));
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
        return ApiResponse.of(responseConverter.toTodoResponse(todo));
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
                .startDate(request.getStartDate())
                .dueDate(request.getDueDate())
                .dueTime(request.getDueTime())
                .sortOrder(request.getSortOrder() != null ? request.getSortOrder() : todo.getSortOrder())
                .progressRate(request.getProgressRate() != null ? request.getProgressRate() : todo.getProgressRate())
                .progressManual(request.getProgressRate() != null ? Boolean.TRUE : todo.getProgressManual())
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

        return ApiResponse.of(responseConverter.toTodoResponse(todo));
    }

    /**
     * 個人TODOを論理削除する。担当者であることを検証する（IDOR対策）。
     *
     * @param todoId Todo ID
     * @param userId 操作ユーザーID
     */
    @Transactional
    public void deletePersonalTodo(Long todoId, Long userId) {
        // 担当者であることを検証（IDOR対策: 他人のTODOはNOT_FOUNDで返す）
        boolean isAssignee = assigneeRepository.existsByTodoIdAndUserId(todoId, userId);
        if (!isAssignee) {
            throw new BusinessException(TodoErrorCode.TODO_NOT_FOUND);
        }
        deleteTodo(todoId);
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
     * 個人TODOを復元する（論理削除の取り消し）。担当者であることを検証する（IDOR対策）。
     *
     * <p>削除EP {@link #deletePersonalTodo(Long, Long)} と同じ認可境界を採用する。
     * 他人の（担当者でない）TODOの復元要求は、他スコープでの ID 存在を漏らさないため
     * {@link TodoErrorCode#TODO_NOT_FOUND}（404）で返す。</p>
     *
     * @param todoId Todo ID
     * @param userId 操作ユーザーID
     */
    @Transactional
    public void restorePersonalTodo(Long todoId, Long userId) {
        // 担当者であることを検証（IDOR対策: 他人のTODOはNOT_FOUNDで返す）
        boolean isAssignee = assigneeRepository.existsByTodoIdAndUserId(todoId, userId);
        if (!isAssignee) {
            throw new BusinessException(TodoErrorCode.TODO_NOT_FOUND);
        }
        restoreTodo(todoId);
    }

    /**
     * 論理削除済みTODOを復元する。
     *
     * @param todoId Todo ID
     */
    @Transactional
    public void restoreTodo(Long todoId) {
        TodoEntity todo = findDeletedTodoOrThrow(todoId);
        Long parentId = todo.getParentId();
        todo.restore();
        todoRepository.save(todo);

        // プロジェクト進捗再計算
        if (todo.getProjectId() != null) {
            projectRepository.recalculateProgress(todo.getProjectId());
        }

        // 親TODO（自動モード）の進捗率再計算
        if (parentId != null) {
            todoProgressService.recalculateAfterChildChange(parentId);
        }

        log.info("TODO復元: id={}", todoId);
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
        return ApiResponse.of(responseConverter.toTodoResponse(todo));
    }

    /**
     * 自分に割り当てられた全TODOを取得する。
     *
     * @param userId ユーザーID
     * @return 自分のTODO一覧
     */
    public ApiResponse<List<TodoResponse>> getMyTodos(Long userId) {
        List<TodoEntity> entities = todoRepository.findMyTodos(userId);
        return ApiResponse.of(responseConverter.toTodoResponseList(entities));
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
        return ApiResponse.of(responseConverter.toTodoResponseList(children));
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
        return ApiResponse.of(responseConverter.toTodoResponse(updated));
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
            return ApiResponse.of(responseConverter.toTodoResponse(updated));
        } else {
            // 自動算出モードへ切替（子の平均から再計算）
            todoProgressService.switchToAutoMode(todo);
            TodoEntity updated = findTodoOrThrow(todoId);
            return ApiResponse.of(responseConverter.toTodoResponse(updated));
        }
    }

    // --- ヘルパーメソッド（他サービスからも利用可能） ---

    /**
     * TODOを取得する。存在しない場合は例外をスローする。
     * {@link TodoStatusService} / {@link TodoAssigneeService} からも利用される。
     */
    public TodoEntity findTodoOrThrow(Long todoId) {
        return todoRepository.findByIdAndDeletedAtIsNull(todoId)
                .orElseThrow(() -> new BusinessException(TodoErrorCode.TODO_NOT_FOUND));
    }

    /**
     * 論理削除済みTODOを取得する。存在しない（未削除も含む）場合は TODO_NOT_FOUND をスローする。
     * 復元（restore）専用ヘルパー。
     */
    public TodoEntity findDeletedTodoOrThrow(Long todoId) {
        return todoRepository.findByIdAndDeletedAtIsNotNull(todoId)
                .orElseThrow(() -> new BusinessException(TodoErrorCode.TODO_NOT_FOUND));
    }

    /**
     * 復元対象の論理削除済み TODO について、path で指定された scope と一致することを検証する。
     *
     * <p>{@link #assertTodoScope(Long, TodoScopeType, Long)} の削除済み版。
     * {@code /api/v1/teams/{teamId}/todos/{id}/restore} などで path scope と
     * 削除済み todo の scope が不一致のとき、他スコープでの ID 存在を漏らさないため
     * {@link TodoErrorCode#TODO_NOT_FOUND}（404）で返す。</p>
     *
     * @param todoId    検証する TODO の ID
     * @param scopeType path のスコープ種別
     * @param scopeId   path のスコープ ID
     * @throws BusinessException 不一致 / 未削除 / 不存在のとき TODO_NOT_FOUND
     */
    public void assertDeletedTodoScope(Long todoId, TodoScopeType scopeType, Long scopeId) {
        TodoEntity todo = findDeletedTodoOrThrow(todoId);
        if (todo.getScopeType() != scopeType
                || !java.util.Objects.equals(todo.getScopeId(), scopeId)) {
            throw new BusinessException(TodoErrorCode.TODO_NOT_FOUND);
        }
    }

    /**
     * path で指定された scope と、対象 TODO の scope が一致することを検証する（F02.3.1 後続 C-7）。
     *
     * <p>IDOR 対策。{@code /api/v1/teams/{teamId}/todos/{id}} などのエンドポイントで、
     * path の {@code teamId} と todo の {@code scopeId} が不一致のとき
     * {@link TodoErrorCode#TODO_NOT_FOUND}（404）で返す（403 ではなく 404 — 他スコープでの ID 存在を漏らさない）。</p>
     *
     * <p>論理削除済み TODO も TODO_NOT_FOUND として扱う。</p>
     *
     * @param todoId    検証する TODO の ID
     * @param scopeType path のスコープ種別
     * @param scopeId   path のスコープ ID
     * @throws BusinessException 不一致 / 削除済み / 不存在のとき TODO_NOT_FOUND
     */
    public void assertTodoScope(Long todoId, TodoScopeType scopeType, Long scopeId) {
        TodoEntity todo = todoRepository.findByIdAndDeletedAtIsNull(todoId)
                .orElseThrow(() -> new BusinessException(TodoErrorCode.TODO_NOT_FOUND));
        if (todo.getScopeType() != scopeType
                || !java.util.Objects.equals(todo.getScopeId(), scopeId)) {
            throw new BusinessException(TodoErrorCode.TODO_NOT_FOUND);
        }
    }

    /**
     * マイルストーンロック中 TODO に対する操作を拒否する（F02.7）。
     *
     * <p>論理削除は分母を減らすだけで達成判定を不当に早めないため例外許可（呼び出し側で
     * 本メソッドを呼ばないことで実現）。本メソッドはステータス変更・編集・担当者変更で使用する。</p>
     *
     * <p>F02.3.1 Phase 2: TODO キャッチボール（{@link TodoHandoffService}）からも呼び出すため
     * public 化している。Handoff はステータス変更を伴うので、ロック中の TODO への引き渡しは
     * {@code MilestoneLockedException}（HTTP 423 Locked）で拒否される。</p>
     *
     * @param todo 対象 TODO
     * @throws MilestoneLockedException ロック中の場合
     */
    public void assertNotMilestoneLocked(TodoEntity todo) {
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
}
