package com.mannschaft.app.todo.service;

import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.timezone.TimezoneContextHolder;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.team.service.TeamService;
import com.mannschaft.app.todo.TodoScopeType;
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
import java.util.Collections;
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
    private final TeamService teamService;
    private final OrganizationService organizationService;

    /**
     * エンティティをレスポンスDTOに変換する（単発取得用）。
     * <p>F02.3.1: ラベル情報は単発で1件取得する。一覧経路では {@link #toTodoResponseList(List)} を使うこと。</p>
     * <p>F02.3.1 後続 B-6: {@code statusLabelId} が NULL の場合は {@code status} enum から
     * SYSTEM 既定ラベルを埋めて返す。これによりフロント側のフォールバック実装に依存しない。</p>
     */
    public TodoResponse toTodoResponse(TodoEntity entity) {
        TodoResponse.TodoStatusLabelInfo labelInfo = resolveLabelInfo(
                entity.getStatusLabelId(), entity.getStatus());
        String scopeSlug = resolveScopeSlugSingle(entity.getScopeType(), entity.getScopeId());
        return toTodoResponseInternal(entity, labelInfo, scopeSlug);
    }

    /**
     * TODO リストを TodoResponse リストに変換する（F02.3.1: ラベル情報を一括取得して N+1 を防ぐ）。
     * <p>F02.3.1 後続 B-6: {@code statusLabelId} が NULL の TODO には SYSTEM 既定ラベルを埋める。</p>
     * <p>slug 根治: TEAM/ORGANIZATION スコープの scopeId → slug をバッチ解決して N+1 を回避する。</p>
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

        // TEAM スコープの scopeId を収集してバッチ slug 解決（TeamService 経由 / ドメイン境界遵守）
        Set<Long> teamScopeIds = entities.stream()
                .filter(e -> e.getScopeType() == TodoScopeType.TEAM && e.getScopeId() != null)
                .map(TodoEntity::getScopeId)
                .collect(Collectors.toSet());
        Map<Long, String> teamSlugMap = teamService.getSlugsByIds(teamScopeIds);

        // ORGANIZATION スコープの scopeId を収集してバッチ slug 解決（OrganizationService 経由 / ドメイン境界遵守）
        Set<Long> orgScopeIds = entities.stream()
                .filter(e -> e.getScopeType() == TodoScopeType.ORGANIZATION && e.getScopeId() != null)
                .map(TodoEntity::getScopeId)
                .collect(Collectors.toSet());
        Map<Long, String> orgSlugMap = organizationService.getSlugsByIds(orgScopeIds);

        return entities.stream()
                .map(e -> {
                    TodoResponse.TodoStatusLabelInfo info = e.getStatusLabelId() == null
                            ? systemDefaultLabelInfo(e.getStatus())
                            : labelMap.get(e.getStatusLabelId());
                    String scopeSlug = resolveScopeSlugFromMaps(
                            e.getScopeType(), e.getScopeId(), teamSlugMap, orgSlugMap);
                    return toTodoResponseInternal(e, info, scopeSlug);
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
        // issue #2544 D 群: キャッシュ安全な View（record）へ射影済みのため record アクセサで読む。
        return todoStatusLabelService.findSystemDefaultByBucket(bucket)
                .map(l -> new TodoResponse.TodoStatusLabelInfo(
                        l.id(), l.name(), l.bucket(), l.color()))
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
        String scopeSlug = resolveScopeSlugSingle(entity.getScopeType(), entity.getScopeId());

        return TodoResponse.builder()
                .id(entity.getId())
                .scope(new TodoResponse.TodoScopeDto(
                        entity.getScopeType().name(),
                        entity.getScopeId(),
                        entity.getProjectId(),
                        entity.getMilestoneId(),
                        scopeSlug))
                .content(new TodoResponse.TodoContentDto(
                        entity.getTitle(),
                        entity.getDescription(),
                        entity.getStartDate(),
                        entity.getProgressRate(),
                        entity.getProgressManual(),
                        entity.getSortOrder()))
                .schedule(new TodoResponse.TodoScheduleDto(
                        entity.getDueDate(),
                        entity.getDueTime(),
                        calculateDaysRemaining(entity.getDueDate()),
                        entity.getLinkedScheduleId()))
                .status(new TodoResponse.TodoStatusDto(
                        entity.getStatus().name(),
                        entity.getPriority().name(),
                        entity.getCompletedAt(),
                        labelInfo))
                .assignees(assignees)
                .hierarchy(new TodoResponse.TodoHierarchyDto(
                        entity.getParentId(),
                        entity.getDepth(),
                        children,
                        (int) childCount,
                        (int) descendantCompleted,
                        (int) descendantTotal))
                .audit(new TodoResponse.TodoAuditDto(
                        entity.getCreatedAt(),
                        entity.getUpdatedAt(),
                        new ProjectResponse.UserInfo(entity.getCreatedBy(), nameMap.getOrDefault(entity.getCreatedBy(), "")),
                        completedByInfo))
                .build();
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
                                                 TodoResponse.TodoStatusLabelInfo labelInfo,
                                                 String scopeSlug) {
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

        return TodoResponse.builder()
                .id(entity.getId())
                .scope(new TodoResponse.TodoScopeDto(
                        entity.getScopeType().name(),
                        entity.getScopeId(),
                        entity.getProjectId(),
                        entity.getMilestoneId(),
                        scopeSlug))
                .content(new TodoResponse.TodoContentDto(
                        entity.getTitle(),
                        entity.getDescription(),
                        entity.getStartDate(),
                        entity.getProgressRate(),
                        entity.getProgressManual(),
                        entity.getSortOrder()))
                .schedule(new TodoResponse.TodoScheduleDto(
                        entity.getDueDate(),
                        entity.getDueTime(),
                        calculateDaysRemaining(entity.getDueDate()),
                        entity.getLinkedScheduleId()))
                .status(new TodoResponse.TodoStatusDto(
                        entity.getStatus().name(),
                        entity.getPriority().name(),
                        entity.getCompletedAt(),
                        labelInfo))
                .assignees(assignees)
                .hierarchy(new TodoResponse.TodoHierarchyDto(
                        entity.getParentId(),
                        entity.getDepth(),
                        java.util.List.of(), // 一覧では統計なし
                        0, 0, 0))
                .audit(new TodoResponse.TodoAuditDto(
                        entity.getCreatedAt(),
                        entity.getUpdatedAt(),
                        new ProjectResponse.UserInfo(entity.getCreatedBy(), nameMap.getOrDefault(entity.getCreatedBy(), "")),
                        completedByInfo))
                .build();
    }

    /**
     * 単発取得用: scopeType と scopeId から slug を解決する。
     * TEAM → TeamService.getSlugById、ORGANIZATION → OrganizationService.getSlugById。
     * PERSONAL や scopeId=null は null を返す。
     * （team/org Entity への直接参照を排除しドメイン境界を遵守する）
     */
    private String resolveScopeSlugSingle(TodoScopeType scopeType, Long scopeId) {
        if (scopeId == null) return null;
        return switch (scopeType) {
            case TEAM -> teamService.getSlugById(scopeId);
            case ORGANIZATION -> organizationService.getSlugById(scopeId);
            case PERSONAL -> null;
        };
    }

    /**
     * 一覧取得用: バッチ解決済みの Map から slug を引く（N+1 回避）。
     */
    private String resolveScopeSlugFromMaps(TodoScopeType scopeType, Long scopeId,
                                              Map<Long, String> teamSlugMap,
                                              Map<Long, String> orgSlugMap) {
        if (scopeId == null) return null;
        return switch (scopeType) {
            case TEAM -> teamSlugMap.get(scopeId);
            case ORGANIZATION -> orgSlugMap.get(scopeId);
            case PERSONAL -> null;
        };
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
