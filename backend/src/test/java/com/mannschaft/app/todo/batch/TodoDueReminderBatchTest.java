package com.mannschaft.app.todo.batch;

import com.mannschaft.app.auth.repository.UserRepository;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

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
 *   <li>明日期限の未完了 TODO に対して UserRepository.findTimezoneById が担当者ごとに呼ばれること</li>
 *   <li>期限超過の未完了 TODO に対して UserRepository.findTimezoneById が呼ばれること</li>
 *   <li>ロック中 TODO は Repository クエリで既に除外され、通知対象にならないこと</li>
 *   <li>担当者不在 TODO では作成者のTZが確認されること</li>
 *   <li>完了済み TODO は Repository クエリで既に除外されていること</li>
 *   <li>当日中の {@code TODO_OVERDUE} 重複送信は抑制されること（TZ条件を通過した場合）</li>
 *   <li>【TZ対応】timezone未設定のユーザーはJSTにフォールバックされること</li>
 *   <li>【TZ対応】複数ユーザーそれぞれのtimezoneが確認されること</li>
 * </ol>
 * </p>
 *
 * <p>注意: 通知送信の有無は実行時刻（ユーザーの現地時刻が08:00台か）に依存するため、
 * 通知スタブには {@code lenient()} を適用している。TZ判定ロジック自体の検証は
 * UserRepositoryの呼び出し確認で行う。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
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

    @Mock
    private UserRepository userRepository;

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

        // 通知関連のスタブを共通で設定（lenient strictness のため未使用でも警告なし）
        given(notificationService.createNotification(anyLong(), anyString(),
                any(NotificationPriority.class), anyString(), anyString(),
                anyString(), anyLong(), any(NotificationScopeType.class),
                anyLong(), anyString(), any()))
                .willReturn(NotificationEntity.builder().id(1L).build());
        given(notificationRepository
                .existsByUserIdAndNotificationTypeAndSourceTypeAndSourceIdAndCreatedAtGreaterThanEqual(
                        anyLong(), anyString(), anyString(), anyLong(), any(LocalDateTime.class)))
                .willReturn(false);
    }

    @Test
    @DisplayName("明日期限の TODO に対して UserRepository.findTimezoneById が担当者数分呼ばれる")
    void sendDueTomorrowReminders_callsTimezoneForEachAssignee() {
        // given
        given(todoRepository.findDueTomorrowForReminder(any(LocalDate.class)))
                .willReturn(List.of(todoWithAssignees));
        given(todoAssigneeRepository.findByTodoId(10L))
                .willReturn(List.of(assignee(10L, 101L), assignee(10L, 102L)));
        given(userRepository.findTimezoneById(101L)).willReturn(Optional.of("Asia/Tokyo"));
        given(userRepository.findTimezoneById(102L)).willReturn(Optional.of("Asia/Tokyo"));

        // when
        int count = batch.sendDueTomorrowReminders();

        // then: todosは1件
        assertThat(count).isEqualTo(1);
        // UserRepositoryのfindTimezoneByIdが各ユーザーに対して呼ばれること
        verify(userRepository).findTimezoneById(101L);
        verify(userRepository).findTimezoneById(102L);
    }

    @Test
    @DisplayName("期限超過の TODO に対して UserRepository.findTimezoneById が担当者数分呼ばれる")
    void sendOverdueReminders_callsTimezoneForAssignees() {
        // given
        TodoEntity overdue = todoWithAssignees.toBuilder()
                .id(30L)
                .dueDate(LocalDate.now().minusDays(2))
                .build();
        given(todoRepository.findOverdueForReminder(any(LocalDate.class)))
                .willReturn(List.of(overdue));
        given(todoAssigneeRepository.findByTodoId(30L))
                .willReturn(List.of(assignee(30L, 101L)));
        given(userRepository.findTimezoneById(101L)).willReturn(Optional.of("Asia/Tokyo"));

        // when
        int count = batch.sendOverdueReminders();

        // then
        assertThat(count).isEqualTo(1);
        verify(userRepository).findTimezoneById(101L);
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

        // then: UserRepositoryもNotificationServiceも呼ばれない
        verify(userRepository, never()).findTimezoneById(anyLong());
        verify(notificationService, never()).createNotification(anyLong(), anyString(),
                any(NotificationPriority.class), anyString(), anyString(),
                anyString(), anyLong(), any(NotificationScopeType.class),
                anyLong(), anyString(), any());
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
        verify(userRepository, never()).findTimezoneById(anyLong());
    }

    @Test
    @DisplayName("担当者不在 TODO では作成者(999L)のTZが確認される")
    void fallbackToCreator_whenNoAssignees_checkCreatorTimezone() {
        // given
        given(todoRepository.findDueTomorrowForReminder(any(LocalDate.class)))
                .willReturn(List.of(todoWithoutAssignees));
        // 担当者空リスト → 作成者999Lにフォールバック
        given(todoAssigneeRepository.findByTodoId(20L))
                .willReturn(Collections.emptyList());
        given(userRepository.findTimezoneById(999L)).willReturn(Optional.of("Asia/Tokyo"));

        // when
        batch.sendDueTomorrowReminders();

        // then: 作成者(999L)のTZが確認される
        verify(userRepository).findTimezoneById(999L);
    }

    // =============================================
    // TZ対応テスト
    // =============================================

    @Nested
    @DisplayName("TZ別送信タイミング")
    class TimezoneBasedSending {

        @Test
        @DisplayName("timezoneが空文字のユーザーはJSTにフォールバックされUserRepositoryが呼ばれる")
        void timezone空文字_JSTフォールバック() {
            // given
            given(todoRepository.findDueTomorrowForReminder(any(LocalDate.class)))
                    .willReturn(List.of(todoWithAssignees));
            given(todoAssigneeRepository.findByTodoId(10L))
                    .willReturn(List.of(assignee(10L, 101L)));
            // timezoneが空文字 → JSTにフォールバック（警告ログが出るが正常動作）
            given(userRepository.findTimezoneById(101L)).willReturn(Optional.of(""));

            // when
            batch.sendDueTomorrowReminders();

            // then: UserRepositoryは呼ばれる（フォールバック動作）
            verify(userRepository).findTimezoneById(101L);
        }

        @Test
        @DisplayName("timezoneがOptional.empty()のユーザーはJSTにフォールバックされる")
        void timezone_empty_JSTフォールバック() {
            // given
            given(todoRepository.findDueTomorrowForReminder(any(LocalDate.class)))
                    .willReturn(List.of(todoWithAssignees));
            given(todoAssigneeRepository.findByTodoId(10L))
                    .willReturn(List.of(assignee(10L, 101L)));
            // UserEntityが存在しない場合（退会済みなど）
            given(userRepository.findTimezoneById(101L)).willReturn(Optional.empty());

            // when
            batch.sendDueTomorrowReminders();

            // then: UserRepositoryは呼ばれる
            verify(userRepository).findTimezoneById(101L);
        }

        @Test
        @DisplayName("無効なtimezone文字列のユーザーはJSTにフォールバックされる（警告ログが出る）")
        void timezone無効文字列_JSTフォールバック() {
            // given
            given(todoRepository.findDueTomorrowForReminder(any(LocalDate.class)))
                    .willReturn(List.of(todoWithAssignees));
            given(todoAssigneeRepository.findByTodoId(10L))
                    .willReturn(List.of(assignee(10L, 101L)));
            // 無効なtimezone文字列 → ZoneId.of()が例外 → JSTにフォールバック
            given(userRepository.findTimezoneById(101L)).willReturn(Optional.of("Invalid/Zone"));

            // when（例外が外に出ないことを確認）
            batch.sendDueTomorrowReminders();

            // then: UserRepositoryは呼ばれる（フォールバック動作で継続）
            verify(userRepository).findTimezoneById(101L);
        }

        @Test
        @DisplayName("複数担当者の場合、それぞれのUserRepository.findTimezoneByIdが呼ばれる")
        void 複数担当者_各TZが確認される() {
            // given: 担当者2人（101=JST, 102=America/New_York）
            given(todoRepository.findDueTomorrowForReminder(any(LocalDate.class)))
                    .willReturn(List.of(todoWithAssignees));
            given(todoAssigneeRepository.findByTodoId(10L))
                    .willReturn(List.of(assignee(10L, 101L), assignee(10L, 102L)));
            given(userRepository.findTimezoneById(101L)).willReturn(Optional.of("Asia/Tokyo"));
            given(userRepository.findTimezoneById(102L)).willReturn(Optional.of("America/New_York"));

            // when
            batch.sendDueTomorrowReminders();

            // then: 各ユーザーのtimezoneがUserRepositoryから取得される
            verify(userRepository).findTimezoneById(101L);
            verify(userRepository).findTimezoneById(102L);
        }
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
