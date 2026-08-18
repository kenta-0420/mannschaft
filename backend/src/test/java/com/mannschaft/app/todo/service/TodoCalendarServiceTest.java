package com.mannschaft.app.todo.service;

import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.team.service.TeamService;
import com.mannschaft.app.todo.TodoPriority;
import com.mannschaft.app.todo.TodoScopeType;
import com.mannschaft.app.todo.TodoStatus;
import com.mannschaft.app.todo.entity.TodoEntity;
import com.mannschaft.app.todo.repository.TodoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TodoCalendarServiceTest {

    @Test
    @DisplayName("退会済みスコープと他人のPERSONAL TODOを返さない")
    void excludesTodosOutsideActiveScopes() {
        var repository = mock(TodoRepository.class);
        var teamService = mock(TeamService.class);
        var organizationService = mock(OrganizationService.class);
        var userRoleRepository = mock(UserRoleRepository.class);
        var service = new TodoCalendarService(
                repository, teamService, organizationService, userRoleRepository);
        Long userId = 10L;
        LocalDate from = LocalDate.of(2030, 1, 1);
        LocalDate to = LocalDate.of(2030, 1, 31);

        TodoEntity activeTeamTodo = todo(1L, TodoScopeType.TEAM, 100L);
        TodoEntity departedOrgTodo = todo(2L, TodoScopeType.ORGANIZATION, 200L);
        TodoEntity anotherPersonalTodo = todo(3L, TodoScopeType.PERSONAL, 99L);
        when(repository.findMyCalendarTodos(userId, from, to))
                .thenReturn(List.of(activeTeamTodo, departedOrgTodo, anotherPersonalTodo));
        when(userRoleRepository.findTeamIdsByUserId(userId)).thenReturn(List.of(100L));
        when(userRoleRepository.findOrganizationIdsByUserId(userId)).thenReturn(List.of());
        when(teamService.getSlugsByIds(java.util.Set.of(100L))).thenReturn(Map.of(100L, "family"));
        when(teamService.getNamesByIds(java.util.Set.of(100L))).thenReturn(Map.of(100L, "家族"));
        when(organizationService.getSlugsByIds(java.util.Set.of())).thenReturn(Map.of());
        when(organizationService.getNamesByIds(java.util.Set.of())).thenReturn(Map.of());

        assertThat(service.getMyCalendarTodos(userId, from, to))
                .extracting(response -> response.id())
                .containsExactly(1L);
    }

    private TodoEntity todo(Long id, TodoScopeType scopeType, Long scopeId) {
        return TodoEntity.builder()
                .id(id)
                .scopeType(scopeType)
                .scopeId(scopeId)
                .title("TODO " + id)
                .status(TodoStatus.OPEN)
                .priority(TodoPriority.MEDIUM)
                .dueDate(LocalDate.of(2030, 1, 15))
                .createdBy(10L)
                .sortOrder(0)
                .build();
    }
}
