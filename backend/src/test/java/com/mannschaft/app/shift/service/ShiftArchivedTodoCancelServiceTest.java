package com.mannschaft.app.shift.service;

import com.mannschaft.app.todo.TodoPriority;
import com.mannschaft.app.todo.TodoScopeType;
import com.mannschaft.app.todo.TodoStatus;
import com.mannschaft.app.todo.entity.TodoEntity;
import com.mannschaft.app.todo.repository.TodoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * {@link ShiftArchivedTodoCancelService} のユニットテスト。F03.5 Phase 4-γ。
 */
@ExtendWith(MockitoExtension.class)
class ShiftArchivedTodoCancelServiceTest {

    @Mock
    private TodoRepository todoRepository;

    @InjectMocks
    private ShiftArchivedTodoCancelService cancelService;

    private static final Long SCHEDULE_ID = 1L;

    // =========================================================
    // cancelShiftLinkedTodos
    // =========================================================

    @Nested
    @DisplayName("cancelShiftLinkedTodos")
    class CancelShiftLinkedTodos {

        @Test
        @DisplayName("OPEN な Todo が存在する場合、CANCELLED に遷移して saveAll する")
        void openなTodoをCANCELLEDに遷移() {
            TodoEntity todo1 = buildShiftLinkedTodo(1L, TodoStatus.OPEN);
            TodoEntity todo2 = buildShiftLinkedTodo(2L, TodoStatus.IN_PROGRESS);
            given(todoRepository.findOpenShiftLinkedTodosByScheduleId(SCHEDULE_ID))
                    .willReturn(List.of(todo1, todo2));

            int result = cancelService.cancelShiftLinkedTodos(SCHEDULE_ID);

            assertThat(result).isEqualTo(2);
            assertThat(todo1.getStatus()).isEqualTo(TodoStatus.CANCELLED);
            assertThat(todo2.getStatus()).isEqualTo(TodoStatus.CANCELLED);
            verify(todoRepository).saveAll(List.of(todo1, todo2));
        }

        @Test
        @DisplayName("対象 Todo が0件の場合は saveAll を呼ばずに 0 を返す")
        void 対象なしは処理なし() {
            given(todoRepository.findOpenShiftLinkedTodosByScheduleId(SCHEDULE_ID))
                    .willReturn(List.of());

            int result = cancelService.cancelShiftLinkedTodos(SCHEDULE_ID);

            assertThat(result).isEqualTo(0);
            verify(todoRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("1件のみの場合も正常にキャンセルする")
        void 一件のみキャンセル() {
            TodoEntity todo = buildShiftLinkedTodo(10L, TodoStatus.OPEN);
            given(todoRepository.findOpenShiftLinkedTodosByScheduleId(SCHEDULE_ID))
                    .willReturn(List.of(todo));

            int result = cancelService.cancelShiftLinkedTodos(SCHEDULE_ID);

            assertThat(result).isEqualTo(1);
            assertThat(todo.getStatus()).isEqualTo(TodoStatus.CANCELLED);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<TodoEntity>> captor = ArgumentCaptor.forClass(List.class);
            verify(todoRepository).saveAll(captor.capture());
            assertThat(captor.getValue()).hasSize(1);
        }

        @Test
        @DisplayName("異なる scheduleId には作用しない（リポジトリが空を返す）")
        void 別スケジュールは対象外() {
            Long otherScheduleId = 999L;
            given(todoRepository.findOpenShiftLinkedTodosByScheduleId(otherScheduleId))
                    .willReturn(List.of());

            int result = cancelService.cancelShiftLinkedTodos(otherScheduleId);

            assertThat(result).isEqualTo(0);
            verify(todoRepository, never()).saveAll(any());
        }
    }

    // =========================================================
    // ヘルパー
    // =========================================================

    private TodoEntity buildShiftLinkedTodo(Long id, TodoStatus status) {
        TodoEntity entity = TodoEntity.builder()
                .scopeType(TodoScopeType.TEAM)
                .scopeId(10L)
                .title("シフト自動作成Todo")
                .status(status)
                .priority(TodoPriority.MEDIUM)
                .createdBy(1L)
                .sortOrder(0)
                .build();
        ReflectionTestUtils.setField(entity, "id", id);
        ReflectionTestUtils.setField(entity, "linkedScheduleId", 1L);
        ReflectionTestUtils.setField(entity, "linkedShiftSlotId", id * 100L);
        return entity;
    }
}
