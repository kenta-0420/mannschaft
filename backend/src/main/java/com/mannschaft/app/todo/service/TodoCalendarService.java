package com.mannschaft.app.todo.service;

import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.team.service.TeamService;
import com.mannschaft.app.todo.TodoScopeType;
import com.mannschaft.app.todo.dto.CalendarTodoResponse;
import com.mannschaft.app.todo.entity.TodoEntity;
import com.mannschaft.app.todo.repository.TodoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * マイカレンダーへ合流する、自分担当 TODO の検索サービス。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TodoCalendarService {

    private final TodoRepository todoRepository;
    private final TeamService teamService;
    private final OrganizationService organizationService;

    /**
     * 本人が担当する未完了 TODO を全スコープから取得する。
     *
     * <p>担当者・未完了・期限・期間交差を DB 側で絞り込む。担当者テーブルを EXISTS で参照するため、
     * 共同担当でも TODO は一件だけ返る。TEAM/ORGANIZATION の slug・名称は ID 集合ごとに一括解決し、
     * カレンダー月表示での N+1 を防ぐ。</p>
     */
    public List<CalendarTodoResponse> getMyCalendarTodos(Long userId, LocalDate from, LocalDate to) {
        List<TodoEntity> todos = todoRepository.findMyCalendarTodos(userId, from, to);

        Set<Long> teamIds = scopeIds(todos, TodoScopeType.TEAM);
        Set<Long> organizationIds = scopeIds(todos, TodoScopeType.ORGANIZATION);
        Map<Long, String> teamSlugs = teamService.getSlugsByIds(teamIds);
        Map<Long, String> teamNames = teamService.getNamesByIds(teamIds);
        Map<Long, String> organizationSlugs = organizationService.getSlugsByIds(organizationIds);
        Map<Long, String> organizationNames = organizationService.getNamesByIds(organizationIds);

        return todos.stream()
                .map(todo -> toResponse(todo, teamSlugs, teamNames, organizationSlugs, organizationNames))
                .toList();
    }

    private Set<Long> scopeIds(List<TodoEntity> todos, TodoScopeType scopeType) {
        return todos.stream()
                .filter(todo -> todo.getScopeType() == scopeType)
                .map(TodoEntity::getScopeId)
                .collect(Collectors.toSet());
    }

    private CalendarTodoResponse toResponse(TodoEntity todo,
                                            Map<Long, String> teamSlugs,
                                            Map<Long, String> teamNames,
                                            Map<Long, String> organizationSlugs,
                                            Map<Long, String> organizationNames) {
        String scopeSlug = switch (todo.getScopeType()) {
            case PERSONAL -> null;
            case TEAM -> teamSlugs.get(todo.getScopeId());
            case ORGANIZATION -> organizationSlugs.get(todo.getScopeId());
        };
        String scopeName = switch (todo.getScopeType()) {
            case PERSONAL -> "個人";
            case TEAM -> teamNames.get(todo.getScopeId());
            case ORGANIZATION -> organizationNames.get(todo.getScopeId());
        };
        return new CalendarTodoResponse(
                todo.getId(), todo.getTitle(), todo.getStartDate(), todo.getDueDate(), todo.getDueTime(),
                todo.getStatus().name(), todo.getPriority().name(), todo.getScopeType().name(), todo.getScopeId(),
                scopeSlug, scopeName, todo.getLinkedScheduleId());
    }
}
