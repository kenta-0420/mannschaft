package com.mannschaft.app.todo.service;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.todo.TodoErrorCode;
import com.mannschaft.app.todo.TodoScopeType;
import com.mannschaft.app.todo.TodoStatus;
import com.mannschaft.app.todo.dto.BulkStatusChangeRequest;
import com.mannschaft.app.todo.dto.ProjectResponse;
import com.mannschaft.app.todo.dto.TodoStatusChangeRequest;
import com.mannschaft.app.todo.dto.TodoStatusChangeResponse;
import com.mannschaft.app.todo.entity.ProjectEntity;
import com.mannschaft.app.todo.entity.TodoEntity;
import com.mannschaft.app.todo.entity.TodoStatusLabelEntity;
import com.mannschaft.app.todo.event.TodoStatusChangedEvent;
import com.mannschaft.app.todo.repository.ProjectRepository;
import com.mannschaft.app.todo.repository.TodoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * TODOステータスサービス。ステータス遷移・一括変更を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TodoStatusService {

    private static final int MAX_BULK_SIZE = 50;

    private final TodoRepository todoRepository;
    private final ProjectRepository projectRepository;
    private final ProjectService projectService;
    private final NameResolverService nameResolverService;
    private final ApplicationEventPublisher eventPublisher;
    private final TodoProgressService todoProgressService;
    private final MilestoneGateService milestoneGateService;
    private final TodoStatusLabelService todoStatusLabelService;
    private final TodoService todoService;

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
        TodoEntity todo = todoService.findTodoOrThrow(todoId);
        // F02.7: ロック中 TODO のステータス変更は 423 Locked
        todoService.assertNotMilestoneLocked(todo);

        TodoStatus oldStatus = todo.getStatus();

        // F02.3.1: status / statusLabelId のいずれか（または両方）を受理
        Long labelId = request.getStatusLabelId();
        TodoStatus newStatus;

        if (labelId != null) {
            // ラベル指定がある場合: ラベルからバケット → status を導出
            TodoStatusLabelEntity label = todoStatusLabelService.findActiveById(labelId);
            todoStatusLabelService.validateLabelForScope(label, todo.getScopeType(), todo.getScopeId());
            newStatus = label.getBucket().toTodoStatus();

            // status も同時に指定されている場合は整合チェック
            if (request.getStatus() != null && !request.getStatus().isBlank()) {
                TodoStatus requested = TodoStatus.valueOf(request.getStatus());
                if (requested != newStatus) {
                    throw new BusinessException(TodoErrorCode.STATUS_LABEL_BUCKET_MISMATCH);
                }
            }
            todo.changeStatusWithLabel(newStatus, labelId, userId);
        } else {
            // 後方互換: status のみ指定。ラベルは更新しない。
            newStatus = TodoStatus.valueOf(request.getStatus());
            todo.changeStatus(newStatus, userId);
        }

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
        // 認可根治（Wave5 todo硬化A・越境一括変更 BOLA 根治）:
        // findByIdInAndDeletedAtIsNull は scope を無視した生取得のため、指定 scope に属する TODO のみに
        // 絞り込む。scopeType/scopeId 不一致（他チーム/組織の id 混入）は対象から除外し、越境変更を封じる。
        List<TodoEntity> todos = todoRepository.findByIdInAndDeletedAtIsNull(request.getTodoIds()).stream()
                .filter(t -> t.getScopeType() == scopeType
                        && java.util.Objects.equals(t.getScopeId(), scopeId))
                .toList();

        // F02.7: ロック中 TODO をスキップする。
        // TODO(F02.7 Phase 15-3 残件): 現在は skippedLockedIds をログ出力のみで、APIレスポンスには含めていない。
        //   レスポンス DTO（List<TodoStatusChangeResponse>）を BulkStatusChangeResponse（skippedLockedIds を含む）に
        //   差し替えるには、既存の呼び出し側（TeamTodoController / PersonalTodoController）とシグネチャ変更を要する。
        //   破壊的変更を避けるため Phase 15-4 以降で対応予定。
        List<Long> skippedLockedIds = new ArrayList<>();
        List<TodoEntity> processable = new ArrayList<>();
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
}
