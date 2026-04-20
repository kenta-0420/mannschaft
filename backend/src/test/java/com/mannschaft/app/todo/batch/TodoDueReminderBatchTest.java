package com.mannschaft.app.todo.batch;

import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.notification.service.NotificationDispatchService;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.todo.TodoPriority;
import com.mannschaft.app.todo.TodoScopeType;
import com.mannschaft.app.todo.TodoStatus;
import com.mannschaft.app.todo.entity.TodoAssigneeEntity;
import com.mannschaft.app.todo.entity.TodoEntity;
import com.mannschaft.app.todo.repository.TodoAssigneeRepository;
import com.mannschaft.app.todo.repository.TodoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link TodoDueReminderBatch} の単体テスト（F04.3 期限リマインダー）。
 *
 * <p>検証対象:
 * <ol>
 *   <li>明日期限の未完了 TODO に対して {@code TODO_DUE_TOMORROW} が担当者数分配信されること</li>
 *   <li>期限超過の未完了 TODO に対して {@code TODO_OVERDUE}（priority=HIGH）が配信されること</li>
 *   <li>ロック中 TODO は Repository クエリで既に除外され、通知対象にならないこと</li>
 *   <li>担当者不在 TODO では作成者へフォールバック通知が送信されること</li>
 *   <li>完了済み TODO は Repository クエリで既に除外されていること</li>
 *   <li>当日中の {@code TODO_OVERDUE} 重複送信は抑制されること</li>
 * </ol>
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class TodoDueReminderBatchTest {

    @Mock
    private TodoRepository todoRepository;

    @Mock
    private TodoAssigneeRepository todoAssigneeRepository;

    @Mock
    private NotificationService notificationService;

    @Mock
    private NotificationDispatchService notificationDispatchService;

    @Mock
    private NotificationRepository notificationRepository;

    @InjectMocks
    private TodoDueReminderBatch batch;

    private TodoEntity todoWithAssignees;
    private TodoEntity todoWithoutAssignees;

    @BeforeEach
    void setUp() {
        // 担当者あり TODO（担当者ID: 101, 102）。個人スコープ・未ロック・未完了。
        todoWithAssignees = TodoEntity.builder()
                .id(10L)
                .scopeType(TodoScopeType.PERSONAL)
                .scopeId(1L)
                .title("企画書レビュー")
                .status(TodoStatus.IN_PROGRESS)
                .priority(TodoPriority.HIGH)
                .dueDate(LocalDate.now().plusDays(1))
                .milestoneLocked(false)
                .createdBy(999L)
                .sortOrder(0)
                .build();

        // 担当者不在 TODO。作成者 ID=999 にフォールバックされる想定。
        todoWithoutAssignees = TodoEntity.builder()
                .id(20L)
                .scopeType(TodoScopeType.TEAM)
                .scopeId(7L)
                .title("名簿更新")
                .status(TodoStatus.OPEN)
                .priority(TodoPriority.MEDIUM)
                .dueDate(LocalDate.now().plusDays(1))
                .milestoneLocked(false)
                .createdBy(999L)
                .sortOrder(0)
                .build();
    }

    @Test
    @DisplayName("明日期限の TODO に対して担当者数分の TODO_DUE_TOMORROW を送信する")
    void sendDueTomorrowReminders_notifiesAllAssignees() {
        // given: 明日期限の TODO が1件、担当者2人
        given(todoRepository.findDueTomorrowForReminder(any(LocalDate.class)))
                .willReturn(List.of(todoWithAssignees));
        given(todoAssigneeRepository.findByTodoId(10L))
                .willReturn(List.of(assignee(10L, 101L), assignee(10L, 102L)));
        given(notificationService.createNotification(anyLong(), anyString(),
                any(NotificationPriority.class), anyString(), anyString(),
                anyString(), anyLong(), any(NotificationScopeType.class),
                anyLong(), anyString(), any()))
                .willReturn(NotificationEntity.builder().id(1L).build());

        // when
        int count = batch.sendDueTomorrowReminders();

        // then
        assertThat(count).isEqualTo(1);
        // 担当者2人へそれぞれ通知
        ArgumentCaptor<Long> userIdCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<NotificationPriority> priorityCaptor =
                ArgumentCaptor.forClass(NotificationPriority.class);
        verify(notificationService, times(2)).createNotification(
                userIdCaptor.capture(), eq("TODO_DUE_TOMORROW"), priorityCaptor.capture(),
                anyString(), anyString(), eq("TODO"), eq(10L),
                eq(NotificationScopeType.PERSONAL), eq(1L),
                eq("/todos/10"), eq(null));
        assertThat(userIdCaptor.getAllValues()).containsExactlyInAnyOrder(101L, 102L);
        assertThat(priorityCaptor.getAllValues()).containsOnly(NotificationPriority.NORMAL);
        // dispatch も2回呼ばれる
        verify(notificationDispatchService, times(2)).dispatch(any(NotificationEntity.class));
        // TODO_DUE_TOMORROW は重複チェック不要のため existsBy は呼ばれない
        verify(notificationRepository, never())
                .existsByUserIdAndNotificationTypeAndSourceTypeAndSourceIdAndCreatedAtGreaterThanEqual(
                        anyLong(), anyString(), anyString(), anyLong(), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("期限超過の TODO に対して TODO_OVERDUE（HIGH）を送信する")
    void sendOverdueReminders_notifiesWithHighPriority() {
        TodoEntity overdue = todoWithAssignees.toBuilder()
                .id(30L)
                .dueDate(LocalDate.now().minusDays(2))
                .build();
        given(todoRepository.findOverdueForReminder(any(LocalDate.class)))
                .willReturn(List.of(overdue));
        given(todoAssigneeRepository.findByTodoId(30L))
                .willReturn(List.of(assignee(30L, 101L)));
        // 当日中の重複送信なし
        given(notificationRepository
                .existsByUserIdAndNotificationTypeAndSourceTypeAndSourceIdAndCreatedAtGreaterThanEqual(
                        eq(101L), eq("TODO_OVERDUE"), eq("TODO"), eq(30L), any(LocalDateTime.class)))
                .willReturn(false);
        given(notificationService.createNotification(anyLong(), anyString(),
                any(NotificationPriority.class), anyString(), anyString(),
                anyString(), anyLong(), any(NotificationScopeType.class),
                anyLong(), anyString(), any()))
                .willReturn(NotificationEntity.builder().id(2L).build());

        // when
        int count = batch.sendOverdueReminders();

        // then
        assertThat(count).isEqualTo(1);
        verify(notificationService).createNotification(
                eq(101L), eq("TODO_OVERDUE"), eq(NotificationPriority.HIGH),
                anyString(), anyString(), eq("TODO"), eq(30L),
                eq(NotificationScopeType.PERSONAL), anyLong(),
                eq("/todos/30"), eq(null));
        verify(notificationDispatchService).dispatch(any(NotificationEntity.class));
    }

    @Test
    @DisplayName("ロック中 TODO は Repository クエリで除外されるため通知されない")
    void lockedTodos_excludedByRepository() {
        // given: Repository は milestone_locked=FALSE 条件を含むため空リストが返る想定
        given(todoRepository.findDueTomorrowForReminder(any(LocalDate.class)))
                .willReturn(Collections.emptyList());
        given(todoRepository.findOverdueForReminder(any(LocalDate.class)))
                .willReturn(Collections.emptyList());

        // when
        batch.run();

        // then: 通知は一切発生しない
        verify(notificationService, never()).createNotification(anyLong(), anyString(),
                any(NotificationPriority.class), anyString(), anyString(),
                anyString(), anyLong(), any(NotificationScopeType.class),
                anyLong(), anyString(), any());
        verify(notificationDispatchService, never()).dispatch(any(NotificationEntity.class));
    }

    @Test
    @DisplayName("担当者不在 TODO では作成者にフォールバック通知する")
    void fallbackToCreator_whenNoAssignees() {
        given(todoRepository.findDueTomorrowForReminder(any(LocalDate.class)))
                .willReturn(List.of(todoWithoutAssignees));
        // 担当者空リスト
        given(todoAssigneeRepository.findByTodoId(20L))
                .willReturn(Collections.emptyList());
        given(notificationService.createNotification(anyLong(), anyString(),
                any(NotificationPriority.class), anyString(), anyString(),
                anyString(), anyLong(), any(NotificationScopeType.class),
                anyLong(), anyString(), any()))
                .willReturn(NotificationEntity.builder().id(3L).build());

        // when
        batch.sendDueTomorrowReminders();

        // then: 作成者(999L) にのみ通知が送られる
        verify(notificationService).createNotification(
                eq(999L), eq("TODO_DUE_TOMORROW"), eq(NotificationPriority.NORMAL),
                anyString(), anyString(), eq("TODO"), eq(20L),
                eq(NotificationScopeType.TEAM), eq(7L),
                eq("/todos/20"), eq(null));
    }

    @Test
    @DisplayName("完了済み TODO は Repository クエリで除外されるため通知対象にならない")
    void completedTodos_excludedByRepository() {
        // given: status=COMPLETED は Repository クエリで除外される前提で空リスト
        given(todoRepository.findDueTomorrowForReminder(any(LocalDate.class)))
                .willReturn(Collections.emptyList());

        // when
        int count = batch.sendDueTomorrowReminders();

        // then
        assertThat(count).isZero();
        verify(notificationService, never()).createNotification(anyLong(), anyString(),
                any(NotificationPriority.class), anyString(), anyString(),
                anyString(), anyLong(), any(NotificationScopeType.class),
                anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("TODO_OVERDUE は当日中に既送信なら重複スキップする")
    void overdue_skipsWhenAlreadySentToday() {
        TodoEntity overdue = todoWithAssignees.toBuilder()
                .id(40L)
                .dueDate(LocalDate.now().minusDays(1))
                .build();
        given(todoRepository.findOverdueForReminder(any(LocalDate.class)))
                .willReturn(List.of(overdue));
        given(todoAssigneeRepository.findByTodoId(40L))
                .willReturn(List.of(assignee(40L, 101L), assignee(40L, 102L)));
        // 101L は当日既に送信済み、102L は未送信
        given(notificationRepository
                .existsByUserIdAndNotificationTypeAndSourceTypeAndSourceIdAndCreatedAtGreaterThanEqual(
                        eq(101L), eq("TODO_OVERDUE"), eq("TODO"), eq(40L), any(LocalDateTime.class)))
                .willReturn(true);
        given(notificationRepository
                .existsByUserIdAndNotificationTypeAndSourceTypeAndSourceIdAndCreatedAtGreaterThanEqual(
                        eq(102L), eq("TODO_OVERDUE"), eq("TODO"), eq(40L), any(LocalDateTime.class)))
                .willReturn(false);
        given(notificationService.createNotification(anyLong(), anyString(),
                any(NotificationPriority.class), anyString(), anyString(),
                anyString(), anyLong(), any(NotificationScopeType.class),
                anyLong(), anyString(), any()))
                .willReturn(NotificationEntity.builder().id(4L).build());

        // when
        batch.sendOverdueReminders();

        // then: 102L のみへ送信
        verify(notificationService, times(1)).createNotification(
                eq(102L), eq("TODO_OVERDUE"), eq(NotificationPriority.HIGH),
                anyString(), anyString(), eq("TODO"), eq(40L),
                any(NotificationScopeType.class), anyLong(), anyString(), any());
        verify(notificationService, never()).createNotification(
                eq(101L), anyString(), any(NotificationPriority.class),
                anyString(), anyString(), anyString(), anyLong(),
                any(NotificationScopeType.class), anyLong(), anyString(), any());
    }

    /**
     * TodoAssigneeEntity のテスト用ビルダー。
     */
    private TodoAssigneeEntity assignee(Long todoId, Long userId) {
        return TodoAssigneeEntity.builder()
                .todoId(todoId)
                .userId(userId)
                .build();
    }
}
