package com.mannschaft.app.todo.service;

import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.timezone.TimezoneContextHolder;
import com.mannschaft.app.todo.TodoStatus;
import com.mannschaft.app.todo.TodoStatusBucket;
import com.mannschaft.app.todo.dto.AssigneeResponse;
import com.mannschaft.app.todo.dto.ProjectResponse;
import com.mannschaft.app.todo.dto.TodoResponse;
import com.mannschaft.app.todo.entity.TodoAssigneeEntity;
import com.mannschaft.app.todo.entity.TodoEntity;
import com.mannschaft.app.todo.entity.TodoStatusLabelEntity;
import com.mannschaft.app.todo.repository.TodoAssigneeRepository;
import com.mannschaft.app.todo.repository.TodoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * TODOエンティティをレスポンスDTOに変換するコンバーター。
 * {@link TodoService} から抽出したDTO変換ヘルパーメソッド群を集約する。
 */
@Component
@RequiredArgsConstructor
public class TodoResponseConverter {

    private final TodoAssigneeRepository assigneeRepository;
    private final TodoRepository todoRepository;
    private final NameResolverService nameResolverService;
    private final TodoStatusLabelService todoStatusLabelService;

    /**
     * エンティティをレスポンスDTOに変換する（一覧用、N+1防止のため統計なし）。
     * <p>F02.3.1: ラベル情報は単発で1件取得する。一覧経路では {@link #toTodoResponseList(List)} を使うこと。</p>
     * <p>F02.3.1 後続 B-6: {@code statusLabelId} が NULL の場合は {@code status} enum から
     * SYSTEM 既定ラベルを埋めて返す。これによりフロント側のフォールバック実装に依存しない。</p>
     */
    public TodoResponse toTodoResponse(TodoEntity entity) {
        TodoResponse.TodoStatusLabelInfo labelInfo = resolveLabelInfo(
                entity.getStatusLabelId(), entity.getStatus());
        return toTodoResponseInternal(entity, labelInfo);
    }

    /**
     * TODO リストを TodoResponse リストに変換する（F02.3.1: ラベル情報を一括取得して N+1 を防ぐ）。
     * <p>F02.3.1 後続 B-6: {@code statusLabelId} が NULL の TODO には SYSTEM 既定ラベルを埋める。</p>
     */
    public List<TodoResponse> toTodoResponseList(List<TodoEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }
        Set<Long> labelIds = entities.stream()
                .map(TodoEntity::getStatusLabelId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<Long, TodoResponse.TodoStatusLabelInfo> labelMap = labelIds.isEmpty()
                ? Map.of()
                : todoStatusLabelService.findActiveByIds(labelIds).stream()
                .collect(Collectors.toMap(
                        TodoStatusLabelEntity::getId,
                        l -> new TodoResponse.TodoStatusLabelInfo(
                                l.getId(), l.getName(), l.getBucket().name(), l.getColor())));

        return entities.stream()
                .map(e -> {
                    TodoResponse.TodoStatusLabelInfo info = e.getStatusLabelId() == null
                            ? systemDefaultLabelInfo(e.getStatus())
                            : labelMap.get(e.getStatusLabelId());
                    return toTodoResponseInternal(e, info);
                })
                .toList();
    }

    /**
     * ラベル情報を解決する。{@code labelId} が NULL の場合は {@code status} から SYSTEM 既定ラベルを返す。
     * 単発取得経路で使用する。
     */
    public TodoResponse.TodoStatusLabelInfo resolveLabelInfo(Long labelId, TodoStatus fallbackStatus) {
        if (labelId == null) {
            return systemDefaultLabelInfo(fallbackStatus);
        }
        return todoStatusLabelService.findActiveByIds(java.util.List.of(labelId)).stream()
                .findFirst()
                .map(l -> new TodoResponse.TodoStatusLabelInfo(
                        l.getId(), l.getName(), l.getBucket().name(), l.getColor()))
                .orElseGet(() -> systemDefaultLabelInfo(fallbackStatus));
    }

    /**
     * {@code TodoStatus} に対応する SYSTEM 既定ラベルの {@link TodoResponse.TodoStatusLabelInfo} を返す。
     * F02.3.1 後続 B-6 で導入。バケット非対応の {@code TodoStatus.CANCELLED} は NULL を返す。
     */
    public TodoResponse.TodoStatusLabelInfo systemDefaultLabelInfo(TodoStatus status) {
        if (status == null || status == TodoStatus.CANCELLED) {
            return null;
        }
        TodoStatusBucket bucket;
        try {
            bucket = TodoStatusBucket.fromTodoStatus(status);
        } catch (IllegalArgumentException ex) {
            return null;
        }
        return todoStatusLabelService.findSystemDefaultByBucket(bucket)
                .map(l -> new TodoResponse.TodoStatusLabelInfo(
                        l.getId(), l.getName(), l.getBucket().name(), l.getColor()))
                .orElse(null);
    }

    /**
     * エンティティをレスポンスDTOに変換する（詳細用、子TODO統計含む）。
     * <p>F02.3.1 後続 B-7: children のラベル情報を一括取得（N+1 解消）。</p>
     */
    public TodoResponse toTodoResponseWithStats(TodoEntity entity) {
        long childCount = todoRepository.countByParentIdAndDeletedAtIsNull(entity.getId());
        long descendantTotal = todoRepository.countDescendants(entity.getId());
        long descendantCompleted = todoRepository.countCompletedDescendants(entity.getId());
        List<TodoEntity> childEntities = todoRepository
                .findByParentIdAndDeletedAtIsNullOrderBySortOrderAsc(entity.getId());
        // F02.3.1 後続 B-7: 一括変換で N+1 を解消
        List<TodoResponse> children = toTodoResponseList(childEntities);

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

        TodoResponse.TodoStatusLabelInfo labelInfo = resolveLabelInfo(
                entity.getStatusLabelId(), entity.getStatus());

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
                entity.getProgressRate(), entity.getProgressManual(),
                // F02.3.1 カスタムステータスラベル
                labelInfo);
    }

    /**
     * 担当者エンティティをレスポンスDTOに変換する。
     */
    public AssigneeResponse toAssigneeResponse(TodoAssigneeEntity entity) {
        Map<Long, String> nameMap = nameResolverService.resolveUserDisplayNames(Set.of(entity.getUserId()));
        return new AssigneeResponse(
                entity.getId(), entity.getUserId(), nameMap.getOrDefault(entity.getUserId(), ""),
                entity.getAssignedBy(), entity.getCreatedAt());
    }

    // --- プライベートメソッド ---

    private TodoResponse toTodoResponseInternal(TodoEntity entity,
                                                 TodoResponse.TodoStatusLabelInfo labelInfo) {
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
                entity.getProgressRate(), entity.getProgressManual(),
                // F02.3.1 カスタムステータスラベル
                labelInfo);
    }

    /**
     * 残日数を算出する。ユーザーのタイムゾーン設定に基づいて「今日」を決定する。
     */
    private Long calculateDaysRemaining(LocalDate dueDate) {
        if (dueDate == null) {
            return null;
        }
        return ChronoUnit.DAYS.between(LocalDate.now(TimezoneContextHolder.get()), dueDate);
    }
}
