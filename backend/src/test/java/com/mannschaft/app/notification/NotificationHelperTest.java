package com.mannschaft.app.notification;

import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.service.NotificationDispatchService;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.notification.service.NotificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link NotificationHelper} の単体テスト。
 * 各モジュールからの通知作成・配信ファサードを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationHelper 単体テスト")
class NotificationHelperTest {

    @Mock
    private NotificationService notificationService;

    @Mock
    private NotificationDispatchService dispatchService;

    @InjectMocks
    private NotificationHelper notificationHelper;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long USER_ID = 1L;
    private static final Long ACTOR_ID = 2L;
    private static final String NOTIFICATION_TYPE = "SCHEDULE_REMINDER";
    private static final String TITLE = "リマインド";
    private static final String BODY = "出欠未回答です";
    private static final String SOURCE_TYPE = "SCHEDULE";
    private static final Long SOURCE_ID = 10L;
    private static final Long SCOPE_ID = 5L;
    private static final String ACTION_URL = "/schedules/10";

    private NotificationEntity createNotificationEntity() {
        return NotificationEntity.builder()
                .userId(USER_ID)
                .notificationType(NOTIFICATION_TYPE)
                .priority(NotificationPriority.NORMAL)
                .title(TITLE)
                .body(BODY)
                .sourceType(SOURCE_TYPE)
                .sourceId(SOURCE_ID)
                .scopeType(NotificationScopeType.TEAM)
                .scopeId(SCOPE_ID)
                .actionUrl(ACTION_URL)
                .actorId(ACTOR_ID)
                .build();
    }

    // ========================================
    // notify (デフォルト優先度)
    // ========================================

    @Nested
    @DisplayName("notify (デフォルト優先度)")
    class NotifyDefault {

        @Test
        @DisplayName("単一通知_正常_作成と配信が実行される")
        void 単一通知_正常_作成と配信が実行される() {
            // Given
            NotificationEntity entity = createNotificationEntity();

            given(notificationService.createNotification(
                    USER_ID, NOTIFICATION_TYPE, NotificationPriority.NORMAL,
                    TITLE, BODY, SOURCE_TYPE, SOURCE_ID,
                    NotificationScopeType.TEAM, SCOPE_ID, ACTION_URL, ACTOR_ID))
                    .willReturn(entity);

            // When
            notificationHelper.notify(USER_ID, NOTIFICATION_TYPE, TITLE, BODY,
                    SOURCE_TYPE, SOURCE_ID, NotificationScopeType.TEAM, SCOPE_ID,
                    ACTION_URL, ACTOR_ID);

            // Then
            verify(notificationService).createNotification(
                    USER_ID, NOTIFICATION_TYPE, NotificationPriority.NORMAL,
                    TITLE, BODY, SOURCE_TYPE, SOURCE_ID,
                    NotificationScopeType.TEAM, SCOPE_ID, ACTION_URL, ACTOR_ID);
            verify(dispatchService).dispatch(entity);
        }
    }

    // ========================================
    // notify (優先度指定)
    // ========================================

    @Nested
    @DisplayName("notify (優先度指定)")
    class NotifyWithPriority {

        @Test
        @DisplayName("優先度指定通知_URGENT_指定優先度で作成される")
        void 優先度指定通知_URGENT_指定優先度で作成される() {
            // Given
            NotificationEntity entity = createNotificationEntity();

            given(notificationService.createNotification(
                    USER_ID, NOTIFICATION_TYPE, NotificationPriority.URGENT,
                    TITLE, BODY, SOURCE_TYPE, SOURCE_ID,
                    NotificationScopeType.TEAM, SCOPE_ID, ACTION_URL, ACTOR_ID))
                    .willReturn(entity);

            // When
            notificationHelper.notify(USER_ID, NOTIFICATION_TYPE, NotificationPriority.URGENT,
                    TITLE, BODY, SOURCE_TYPE, SOURCE_ID,
                    NotificationScopeType.TEAM, SCOPE_ID, ACTION_URL, ACTOR_ID);

            // Then
            verify(notificationService).createNotification(
                    USER_ID, NOTIFICATION_TYPE, NotificationPriority.URGENT,
                    TITLE, BODY, SOURCE_TYPE, SOURCE_ID,
                    NotificationScopeType.TEAM, SCOPE_ID, ACTION_URL, ACTOR_ID);
            verify(dispatchService).dispatch(entity);
        }
    }

    // ========================================
    // notifyAll (デフォルト優先度)
    // ========================================

    @Nested
    @DisplayName("notifyAll (デフォルト優先度)")
    class NotifyAllDefault {

        @Test
        @DisplayName("一括通知_複数ユーザー_全員に通知が送信される")
        void 一括通知_複数ユーザー_全員に通知が送信される() {
            // Given
            List<Long> userIds = List.of(1L, 2L, 3L);
            NotificationEntity entity = createNotificationEntity();

            given(notificationService.createNotification(
                    any(), eq(NOTIFICATION_TYPE), eq(NotificationPriority.NORMAL),
                    eq(TITLE), eq(BODY), eq(SOURCE_TYPE), eq(SOURCE_ID),
                    eq(NotificationScopeType.TEAM), eq(SCOPE_ID), eq(ACTION_URL), eq(ACTOR_ID)))
                    .willReturn(entity);

            // When
            notificationHelper.notifyAll(userIds, NOTIFICATION_TYPE, TITLE, BODY,
                    SOURCE_TYPE, SOURCE_ID, NotificationScopeType.TEAM, SCOPE_ID,
                    ACTION_URL, ACTOR_ID);

            // Then
            verify(notificationService, times(3)).createNotification(
                    any(), eq(NOTIFICATION_TYPE), eq(NotificationPriority.NORMAL),
                    eq(TITLE), eq(BODY), eq(SOURCE_TYPE), eq(SOURCE_ID),
                    eq(NotificationScopeType.TEAM), eq(SCOPE_ID), eq(ACTION_URL), eq(ACTOR_ID));
            verify(dispatchService, times(3)).dispatch(entity);
        }

        @Test
        @DisplayName("一括通知_空リスト_通知なし")
        void 一括通知_空リスト_通知なし() {
            // Given
            List<Long> userIds = List.of();

            // When
            notificationHelper.notifyAll(userIds, NOTIFICATION_TYPE, TITLE, BODY,
                    SOURCE_TYPE, SOURCE_ID, NotificationScopeType.TEAM, SCOPE_ID,
                    ACTION_URL, ACTOR_ID);

            // Then
            verify(notificationService, never()).createNotification(
                    any(), any(), any(NotificationPriority.class),
                    any(), any(), any(), any(),
                    any(), any(), any(), any());
            verify(dispatchService, never()).dispatch(any());
        }

        @Test
        @DisplayName("一括通知_一部失敗_残りは継続して送信される")
        void 一括通知_一部失敗_残りは継続して送信される() {
            // Given
            List<Long> userIds = List.of(1L, 2L, 3L);
            NotificationEntity entity = createNotificationEntity();

            // 1人目は成功、2人目は失敗、3人目は成功
            given(notificationService.createNotification(
                    eq(1L), eq(NOTIFICATION_TYPE), eq(NotificationPriority.NORMAL),
                    eq(TITLE), eq(BODY), eq(SOURCE_TYPE), eq(SOURCE_ID),
                    eq(NotificationScopeType.TEAM), eq(SCOPE_ID), eq(ACTION_URL), eq(ACTOR_ID)))
                    .willReturn(entity);
            given(notificationService.createNotification(
                    eq(2L), eq(NOTIFICATION_TYPE), eq(NotificationPriority.NORMAL),
                    eq(TITLE), eq(BODY), eq(SOURCE_TYPE), eq(SOURCE_ID),
                    eq(NotificationScopeType.TEAM), eq(SCOPE_ID), eq(ACTION_URL), eq(ACTOR_ID)))
                    .willThrow(new RuntimeException("通知作成失敗"));
            given(notificationService.createNotification(
                    eq(3L), eq(NOTIFICATION_TYPE), eq(NotificationPriority.NORMAL),
                    eq(TITLE), eq(BODY), eq(SOURCE_TYPE), eq(SOURCE_ID),
                    eq(NotificationScopeType.TEAM), eq(SCOPE_ID), eq(ACTION_URL), eq(ACTOR_ID)))
                    .willReturn(entity);

            // When
            notificationHelper.notifyAll(userIds, NOTIFICATION_TYPE, TITLE, BODY,
                    SOURCE_TYPE, SOURCE_ID, NotificationScopeType.TEAM, SCOPE_ID,
                    ACTION_URL, ACTOR_ID);

            // Then - 3人全員分のcreateNotificationが呼ばれ、成功した2人分のdispatchが実行される
            verify(notificationService, times(3)).createNotification(
                    any(), eq(NOTIFICATION_TYPE), eq(NotificationPriority.NORMAL),
                    eq(TITLE), eq(BODY), eq(SOURCE_TYPE), eq(SOURCE_ID),
                    eq(NotificationScopeType.TEAM), eq(SCOPE_ID), eq(ACTION_URL), eq(ACTOR_ID));
            verify(dispatchService, times(2)).dispatch(entity);
        }
    }

    // ========================================
    // notifyAll (優先度指定)
    // ========================================

    @Nested
    @DisplayName("notifyAll (優先度指定)")
    class NotifyAllWithPriority {

        @Test
        @DisplayName("優先度指定一括通知_HIGH_指定優先度で全員に送信される")
        void 優先度指定一括通知_HIGH_指定優先度で全員に送信される() {
            // Given
            List<Long> userIds = List.of(1L, 2L);
            NotificationEntity entity = createNotificationEntity();

            given(notificationService.createNotification(
                    any(), eq(NOTIFICATION_TYPE), eq(NotificationPriority.HIGH),
                    eq(TITLE), eq(BODY), eq(SOURCE_TYPE), eq(SOURCE_ID),
                    eq(NotificationScopeType.TEAM), eq(SCOPE_ID), eq(ACTION_URL), eq(ACTOR_ID)))
                    .willReturn(entity);

            // When
            notificationHelper.notifyAll(userIds, NOTIFICATION_TYPE, NotificationPriority.HIGH,
                    TITLE, BODY, SOURCE_TYPE, SOURCE_ID,
                    NotificationScopeType.TEAM, SCOPE_ID, ACTION_URL, ACTOR_ID);

            // Then
            verify(notificationService, times(2)).createNotification(
                    any(), eq(NOTIFICATION_TYPE), eq(NotificationPriority.HIGH),
                    eq(TITLE), eq(BODY), eq(SOURCE_TYPE), eq(SOURCE_ID),
                    eq(NotificationScopeType.TEAM), eq(SCOPE_ID), eq(ACTION_URL), eq(ACTOR_ID));
            verify(dispatchService, times(2)).dispatch(entity);
        }
    }
}
