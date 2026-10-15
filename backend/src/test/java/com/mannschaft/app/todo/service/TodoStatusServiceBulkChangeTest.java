package com.mannschaft.app.todo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.todo.TodoScopeType;
import com.mannschaft.app.todo.TodoStatus;
import com.mannschaft.app.todo.dto.BulkStatusChangeRequest;
import com.mannschaft.app.todo.dto.BulkStatusChangeResponse;
import com.mannschaft.app.todo.entity.TodoEntity;
import com.mannschaft.app.todo.repository.ProjectRepository;
import com.mannschaft.app.todo.repository.TodoRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TodoStatusServiceBulkChangeTest {

    @Mock private TodoRepository todoRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private ProjectService projectService;
    @Mock private NameResolverService nameResolverService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private TodoProgressService todoProgressService;
    @Mock private MilestoneGateService milestoneGateService;
    @Mock private TodoStatusLabelService todoStatusLabelService;
    @Mock private TodoService todoService;
    @InjectMocks private TodoStatusService service;

    @Test
    void ロックなしは従来のdata配列と空のスキップ配列を返す() throws Exception {
        TodoEntity open = todo(1L, 10L, false);
        when(todoRepository.findByIdInAndDeletedAtIsNull(List.of(1L))).thenReturn(List.of(open));

        BulkStatusChangeResponse result = change(List.of(1L));

        assertThat(result.getData()).extracting("id").containsExactly(1L);
        assertThat(result.getSkippedLockedIds()).isEmpty();
        assertThat(open.getStatus()).isEqualTo(TodoStatus.IN_PROGRESS);
        assertThat(new ObjectMapper().valueToTree(result).path("data").isArray()).isTrue();
        assertThat(new ObjectMapper().valueToTree(result).path("skippedLockedIds").isArray()).isTrue();
        verify(todoRepository).save(open);
        verify(eventPublisher).publishEvent(org.mockito.ArgumentMatchers.any(
                com.mannschaft.app.todo.event.TodoStatusChangedEvent.class));
    }

    @Test
    void 混在時はロック済みのみスキップし越境と論理削除IDを漏らさない() {
        TodoEntity open = todo(1L, 10L, false);
        TodoEntity locked = todo(2L, 10L, true);
        TodoEntity foreign = todo(3L, 20L, true);
        // 論理削除 ID=4 は repository の DeletedAtIsNull 条件で返されない。
        List<Long> ids = List.of(1L, 2L, 3L, 4L);
        when(todoRepository.findByIdInAndDeletedAtIsNull(ids)).thenReturn(List.of(open, locked, foreign));

        BulkStatusChangeResponse result = change(ids);

        assertThat(result.getData()).extracting("id").containsExactly(1L);
        assertThat(result.getSkippedLockedIds()).containsExactly(2L);
        assertThat(locked.getStatus()).isEqualTo(TodoStatus.OPEN);
        assertThat(foreign.getStatus()).isEqualTo(TodoStatus.OPEN);
        verify(todoRepository, never()).save(locked);
        verify(todoRepository, never()).save(foreign);
        verifyNoInteractions(projectRepository, milestoneGateService, todoProgressService);
    }

    @Test
    void 全件ロック時は副作用なしで空のdataを返す() {
        TodoEntity locked = todo(2L, 10L, true);
        when(todoRepository.findByIdInAndDeletedAtIsNull(List.of(2L))).thenReturn(List.of(locked));

        BulkStatusChangeResponse result = change(List.of(2L));

        assertThat(result.getData()).isEmpty();
        assertThat(result.getSkippedLockedIds()).containsExactly(2L);
        assertThat(locked.getStatus()).isEqualTo(TodoStatus.OPEN);
        verify(todoRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(eventPublisher, projectRepository, milestoneGateService, todoProgressService);
    }

    private BulkStatusChangeResponse change(List<Long> ids) {
        return service.bulkChangeStatus(TodoScopeType.TEAM, 10L,
                new BulkStatusChangeRequest(ids, "IN_PROGRESS"), 100L);
    }

    private TodoEntity todo(Long id, Long scopeId, boolean locked) {
        return TodoEntity.builder()
                .id(id).scopeType(TodoScopeType.TEAM).scopeId(scopeId)
                .status(TodoStatus.OPEN).milestoneLocked(locked).build();
    }
}
