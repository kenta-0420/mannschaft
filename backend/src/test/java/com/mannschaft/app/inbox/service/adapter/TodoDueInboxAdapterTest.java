package com.mannschaft.app.inbox.service.adapter;

import com.mannschaft.app.inbox.InboxSourceType;
import com.mannschaft.app.inbox.service.InboxPriorityNormalizer;
import com.mannschaft.app.todo.TodoStatus;
import com.mannschaft.app.todo.entity.TodoEntity;
import com.mannschaft.app.todo.repository.TodoAssigneeRepository;
import com.mannschaft.app.todo.repository.TodoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TodoDueInboxAdapter#isVisibleTo} のユニットテスト（Phase3b E-3 / IDOR 防止）。
 *
 * <p>triage 書き込み前の本人宛て判定（担当者 ∧ 未完了 ∧ due_date あり）を網羅する。
 * <b>現状の実装挙動の固定</b>が目的（仕様変更しない）。
 *
 * <p>可視条件（AND 結合）:
 * <ul>
 *   <li>{@code todo_assignees} に (sourceId, userId) が存在する（本人担当）</li>
 *   <li>TODO が論理削除されていない</li>
 *   <li>status ∈ {OPEN, IN_PROGRESS}（active）</li>
 *   <li>due_date が非 NULL</li>
 * </ul>
 */
@DisplayName("TodoDueInboxAdapter#isVisibleTo IDOR 防止")
class TodoDueInboxAdapterTest {

    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final Long TODO_ID = 100L;

    private final TodoRepository todoRepository = mock(TodoRepository.class);
    private final TodoAssigneeRepository todoAssigneeRepository = mock(TodoAssigneeRepository.class);
    private final InboxPriorityNormalizer normalizer = new InboxPriorityNormalizer();

    private final TodoDueInboxAdapter adapter =
            new TodoDueInboxAdapter(todoRepository, todoAssigneeRepository, normalizer);

    @Test
    @DisplayName("sourceType() は TODO_DUE")
    void sourceType_isTodoDue() {
        assertThat(adapter.sourceType()).isEqualTo(InboxSourceType.TODO_DUE);
    }

    @Test
    @DisplayName("本人担当 ∧ 未完了 ∧ due_date あり → 可視 true")
    void selfAssignedActiveWithDue_visible() {
        when(todoAssigneeRepository.existsByTodoIdAndUserId(TODO_ID, USER_ID)).thenReturn(true);
        when(todoRepository.findByIdAndDeletedAtIsNull(TODO_ID))
                .thenReturn(Optional.of(todo(TodoStatus.OPEN, LocalDate.now())));

        assertThat(adapter.isVisibleTo(USER_ID, TODO_ID)).isTrue();
    }

    @Test
    @DisplayName("他人の TODO（担当者でない）→ 不可視 false（IDOR 拒否・DB は引かない）")
    void notAssignee_notVisible() {
        when(todoAssigneeRepository.existsByTodoIdAndUserId(TODO_ID, OTHER_USER_ID)).thenReturn(false);

        assertThat(adapter.isVisibleTo(OTHER_USER_ID, TODO_ID)).isFalse();
        // 担当でなければ TODO 本体を引かずに短絡する
        verify(todoRepository, never()).findByIdAndDeletedAtIsNull(TODO_ID);
    }

    @Test
    @DisplayName("担当だが削除済み（findByIdAndDeletedAtIsNull が空）→ 不可視 false")
    void assigneeButDeleted_notVisible() {
        when(todoAssigneeRepository.existsByTodoIdAndUserId(TODO_ID, USER_ID)).thenReturn(true);
        when(todoRepository.findByIdAndDeletedAtIsNull(TODO_ID)).thenReturn(Optional.empty());

        assertThat(adapter.isVisibleTo(USER_ID, TODO_ID)).isFalse();
    }

    @Test
    @DisplayName("担当だが完了済み（COMPLETED）→ 不可視 false")
    void assigneeButCompleted_notVisible() {
        when(todoAssigneeRepository.existsByTodoIdAndUserId(TODO_ID, USER_ID)).thenReturn(true);
        when(todoRepository.findByIdAndDeletedAtIsNull(TODO_ID))
                .thenReturn(Optional.of(todo(TodoStatus.COMPLETED, LocalDate.now())));

        assertThat(adapter.isVisibleTo(USER_ID, TODO_ID)).isFalse();
    }

    @Test
    @DisplayName("担当だがキャンセル（CANCELLED）→ 不可視 false")
    void assigneeButCancelled_notVisible() {
        when(todoAssigneeRepository.existsByTodoIdAndUserId(TODO_ID, USER_ID)).thenReturn(true);
        when(todoRepository.findByIdAndDeletedAtIsNull(TODO_ID))
                .thenReturn(Optional.of(todo(TodoStatus.CANCELLED, LocalDate.now())));

        assertThat(adapter.isVisibleTo(USER_ID, TODO_ID)).isFalse();
    }

    @Test
    @DisplayName("担当・未完了だが due_date が NULL → 不可視 false")
    void assigneeActiveButNoDue_notVisible() {
        when(todoAssigneeRepository.existsByTodoIdAndUserId(TODO_ID, USER_ID)).thenReturn(true);
        when(todoRepository.findByIdAndDeletedAtIsNull(TODO_ID))
                .thenReturn(Optional.of(todo(TodoStatus.IN_PROGRESS, null)));

        assertThat(adapter.isVisibleTo(USER_ID, TODO_ID)).isFalse();
    }

    @Test
    @DisplayName("IN_PROGRESS も active 扱いで可視 true")
    void inProgressActive_visible() {
        when(todoAssigneeRepository.existsByTodoIdAndUserId(TODO_ID, USER_ID)).thenReturn(true);
        when(todoRepository.findByIdAndDeletedAtIsNull(TODO_ID))
                .thenReturn(Optional.of(todo(TodoStatus.IN_PROGRESS, LocalDate.now())));

        assertThat(adapter.isVisibleTo(USER_ID, TODO_ID)).isTrue();
    }

    // ─── ヘルパー ───────────────────────────────────────────────

    private TodoEntity todo(TodoStatus status, LocalDate dueDate) {
        return TodoEntity.builder()
                .id(TODO_ID)
                .title("t")
                .status(status)
                .dueDate(dueDate)
                .build();
    }
}
