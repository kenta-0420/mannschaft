package com.mannschaft.app.family.service;

import com.mannschaft.app.event.EventScopeType;
import com.mannschaft.app.event.entity.EventAttendanceMode;
import com.mannschaft.app.event.entity.EventEntity;
import com.mannschaft.app.event.entity.EventVisibility;
import com.mannschaft.app.event.repository.EventRepository;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.service.NotificationDispatchService;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.role.repository.UserRoleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link EventEndReminderBatchService} のユニットテスト。F03.12 §16。
 */
@ExtendWith(MockitoExtension.class)
class EventEndReminderBatchServiceTest {

    @Mock
    private EventRepository eventRepository;

    @Mock
    private NotificationService notificationService;

    @Mock
    private NotificationDispatchService dispatchService;

    @Mock
    private UserRoleRepository userRoleRepository;

    @InjectMocks
    private EventEndReminderBatchService batchService;

    // テスト定数
    private static final Long EVENT_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final Long ORGANIZER_USER_ID = 100L;
    private static final Long ADMIN_USER_ID_1 = 201L;
    private static final Long ADMIN_USER_ID_2 = 202L;

    // =========================================================
    // runEndReminderCheck
    // =========================================================

    @Nested
    @DisplayName("runEndReminderCheck")
    class RunEndReminderCheck {

        @Test
        @DisplayName("1回目リマインド送信: count=0のイベント → NORMAL優先度で主催者に通知。F03.12 Phase11: actionUrl にチームID 含む")
        void 一回目リマインド送信() {
            // Arrange: count=0（未送信）の終了済みイベント
            EventEntity event = buildEventWithReminderCount(0);
            given(eventRepository.findDismissalReminderTargets(
                    any(LocalDateTime.class), any(LocalDateTime.class), anyInt()))
                    .willReturn(List.of(event));
            given(notificationService.createNotification(
                    anyLong(), any(), any(NotificationPriority.class),
                    any(), any(), any(), anyLong(),
                    any(NotificationScopeType.class), anyLong(), any(), any()))
                    .willReturn(buildNotification(ORGANIZER_USER_ID));

            // Act
            batchService.runEndReminderCheck();

            // Assert: NORMAL 優先度で主催者に送信。actionUrl は /teams/{teamId}/events/{eventId} 形式
            String expectedActionUrl = "/teams/" + TEAM_ID + "/events/" + EVENT_ID;
            verify(notificationService).createNotification(
                    eq(ORGANIZER_USER_ID),
                    eq("EVENT_DISMISSAL_REMINDER"),
                    eq(NotificationPriority.NORMAL),
                    any(), any(), eq("EVENT"), eq(EVENT_ID),
                    any(NotificationScopeType.class), anyLong(), eq(expectedActionUrl), any());
            verify(dispatchService).dispatch(any(NotificationEntity.class));
            // ADMINには通知しない
            verify(userRoleRepository, never()).findUserIdsByTeamIdAndRoleName(any(), any());
        }

        @Test
        @DisplayName("2回目リマインド送信: count=1 → HIGH優先度で主催者のみに通知")
        void 二回目リマインド送信() {
            // Arrange: count=1（1回目送信済み）
            EventEntity event = buildEventWithReminderCount(1);
            given(eventRepository.findDismissalReminderTargets(
                    any(LocalDateTime.class), any(LocalDateTime.class), anyInt()))
                    .willReturn(List.of(event));
            given(notificationService.createNotification(
                    anyLong(), any(), any(NotificationPriority.class),
                    any(), any(), any(), anyLong(),
                    any(NotificationScopeType.class), anyLong(), any(), any()))
                    .willReturn(buildNotification(ORGANIZER_USER_ID));

            // Act
            batchService.runEndReminderCheck();

            // Assert: HIGH 優先度で主催者に送信
            verify(notificationService).createNotification(
                    eq(ORGANIZER_USER_ID),
                    eq("EVENT_DISMISSAL_REMINDER"),
                    eq(NotificationPriority.HIGH),
                    any(), any(), eq("EVENT"), eq(EVENT_ID),
                    any(NotificationScopeType.class), anyLong(), any(), any());
            // ADMINには通知しない
            verify(userRoleRepository, never()).findUserIdsByTeamIdAndRoleName(any(), any());
        }

        @Test
        @DisplayName("3回目リマインド送信: count=2 → URGENT優先度でADMIN全員にも通知")
        void 三回目リマインド送信_ADMIN全員に通知() {
            // Arrange: count=2（2回目送信済み）
            EventEntity event = buildEventWithReminderCount(2);
            given(eventRepository.findDismissalReminderTargets(
                    any(LocalDateTime.class), any(LocalDateTime.class), anyInt()))
                    .willReturn(List.of(event));
            given(notificationService.createNotification(
                    anyLong(), any(), any(NotificationPriority.class),
                    any(), any(), any(), anyLong(),
                    any(NotificationScopeType.class), anyLong(), any(), any()))
                    .willAnswer(inv -> buildNotification(inv.getArgument(0)));
            // チームADMINリスト: 主催者(100) + 別ADMIN(201・202)
            given(userRoleRepository.findUserIdsByTeamIdAndRoleName(TEAM_ID, "ADMIN"))
                    .willReturn(List.of(ORGANIZER_USER_ID, ADMIN_USER_ID_1, ADMIN_USER_ID_2));

            // Act
            batchService.runEndReminderCheck();

            // Assert: 主催者 + ADMIN 2名（計3名）に URGENT 通知（主催者は重複排除で2回目）
            // 主催者は sendReminderByCount 内の 3回目ブランチで一度呼ばれる
            // sendAdminReminders では主催者を除外するので ADMIN_USER_ID_1・2 のみ
            verify(notificationService).createNotification(
                    eq(ORGANIZER_USER_ID),
                    eq("EVENT_DISMISSAL_REMINDER"),
                    eq(NotificationPriority.URGENT),
                    any(), any(), any(), any(), any(), any(), any(), any());
            verify(notificationService).createNotification(
                    eq(ADMIN_USER_ID_1),
                    eq("EVENT_DISMISSAL_REMINDER"),
                    eq(NotificationPriority.URGENT),
                    any(), any(), any(), any(), any(), any(), any(), any());
            verify(notificationService).createNotification(
                    eq(ADMIN_USER_ID_2),
                    eq("EVENT_DISMISSAL_REMINDER"),
                    eq(NotificationPriority.URGENT),
                    any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("解散済みイベントはスキップ: findDismissalReminderTargets が空 → 通知なし")
        void 解散済みイベントはスキップ() {
            // Arrange: dismissal_notification_sent_at が設定済み → リポジトリが空リストを返す
            given(eventRepository.findDismissalReminderTargets(
                    any(LocalDateTime.class), any(LocalDateTime.class), anyInt()))
                    .willReturn(List.of());

            // Act
            batchService.runEndReminderCheck();

            // Assert: 通知なし
            verify(notificationService, never()).createNotification(
                    anyLong(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("上限3回到達済みはスキップ: count=3 → 通知なし（リポジトリ除外済みを想定）")
        void 上限到達済みはスキップ() {
            // Arrange: count=3（上限到達済み）→ findDismissalReminderTargets が除外済み
            EventEntity event = buildEventWithReminderCount(3);
            given(eventRepository.findDismissalReminderTargets(
                    any(LocalDateTime.class), any(LocalDateTime.class), anyInt()))
                    .willReturn(List.of(event));

            // Act
            batchService.runEndReminderCheck();

            // Assert: count >= MAX_REMINDER_COUNT でスキップ
            verify(notificationService, never()).createNotification(
                    anyLong(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        }
    }

    // =========================================================
    // テストヘルパー
    // =========================================================

    /**
     * 指定リマインド回数のイベントエンティティを構築する。
     *
     * @param reminderCount リマインド送信回数（0〜3）
     * @return イベントエンティティ
     */
    private EventEntity buildEventWithReminderCount(int reminderCount) {
        EventEntity event = EventEntity.builder()
                .scopeType(EventScopeType.TEAM)
                .scopeId(TEAM_ID)
                .slug("test-event")
                .subtitle("テストイベント")
                .createdBy(ORGANIZER_USER_ID)
                .attendanceMode(EventAttendanceMode.REGISTRATION)
                .visibility(EventVisibility.MEMBERS_ONLY)
                .build();
        // BaseEntity.id は @GeneratedValue 由来で Lombok ビルダーから設定できないため、
        // リフレクションで明示的に注入する。バッチが event.getId() を通知の sourceId として使うため必須。
        ReflectionTestUtils.setField(event, "id", EVENT_ID);

        // incrementOrganizerReminder を指定回数呼び出してカウントを設定
        for (int i = 0; i < reminderCount; i++) {
            event.incrementOrganizerReminder();
        }
        return event;
    }

    /**
     * テスト用の通知エンティティを構築する。
     *
     * @param userId 通知先ユーザーID
     * @return 通知エンティティ
     */
    private NotificationEntity buildNotification(Long userId) {
        return NotificationEntity.builder()
                .userId(userId)
                .notificationType("EVENT_DISMISSAL_REMINDER")
                .title("テスト通知")
                .body("テスト本文")
                .sourceType("EVENT")
                .sourceId(EVENT_ID)
                .scopeType(NotificationScopeType.PERSONAL)
                .scopeId(userId)
                .build();
    }
}
