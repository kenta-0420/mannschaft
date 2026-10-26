package com.mannschaft.app.common.visibility.guard;

import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.notification.NotificationMapper;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.service.NotificationDispatchService;
import com.mannschaft.app.notification.service.NotificationPreferenceService;
import com.mannschaft.app.notification.service.PushSubscriptionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F00 Phase F: {@link NotificationDispatchService#dispatch} の visibility
 * ガードテスト (二重防御 §11.1)。
 *
 * <p>{@code NotificationService.createNotification} を経由しない経路
 * (DB から復元した古い通知の再送等) に対しても visibility ガードが
 * 効くことを担保する。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("F00 Phase F: NotificationDispatchService 配信ガードテスト")
class NotificationDispatchServiceVisibilityGuardTest {

    @Mock
    private PushSubscriptionService pushSubscriptionService;

    @Mock
    private NotificationPreferenceService preferenceService;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private NotificationMapper notificationMapper;

    @Mock
    private ContentVisibilityChecker visibilityChecker;

    @InjectMocks
    private NotificationDispatchService dispatchService;

    private static final Long USER_ID = 100L;
    private static final Long CONTENT_ID = 555L;

    private NotificationEntity buildNotification(String sourceType, Long sourceId) {
        return NotificationEntity.builder()
                .userId(USER_ID)
                .notificationType("BLOG_MENTION")
                .priority(NotificationPriority.NORMAL)
                .title("テスト通知")
                .body("body")
                .sourceType(sourceType)
                .sourceId(sourceId)
                .scopeType(NotificationScopeType.PERSONAL)
                .scopeId(null)
                .build();
    }

    @Test
    @DisplayName("dispatch: visibility deny → 配信スキップ (WebSocket / Push 共に呼ばれず)")
    void dispatch_skips_send_when_visibility_denied() {
        // Given
        NotificationEntity notification = buildNotification("BLOG_POST", CONTENT_ID);
        given(visibilityChecker.canView(eq(ReferenceType.BLOG_POST), eq(CONTENT_ID), eq(USER_ID)))
                .willReturn(false);

        // When
        dispatchService.dispatch(notification);

        // Then: visibility deny で preference / WebSocket / Push どれも呼ばれない
        verify(visibilityChecker).canView(eq(ReferenceType.BLOG_POST), eq(CONTENT_ID), eq(USER_ID));
        verify(preferenceService, never()).isNotificationEnabled(any(), any(), any());
        verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
        verify(pushSubscriptionService, never()).listSubscriptions(any());
    }

    @Test
    @DisplayName("dispatch: visibility allow → preference チェックに進む")
    void dispatch_proceeds_when_visibility_allowed() {
        // Given
        NotificationEntity notification = buildNotification("BLOG_POST", CONTENT_ID);
        given(visibilityChecker.canView(eq(ReferenceType.BLOG_POST), eq(CONTENT_ID), eq(USER_ID)))
                .willReturn(true);
        given(preferenceService.isNotificationEnabled(eq(USER_ID), any(), any())).willReturn(false);

        // When
        dispatchService.dispatch(notification);

        // Then: visibility 通過後、preference チェックに進む
        verify(visibilityChecker).canView(eq(ReferenceType.BLOG_POST), eq(CONTENT_ID), eq(USER_ID));
        verify(preferenceService).isNotificationEnabled(eq(USER_ID), any(), any());
    }

    @Test
    @DisplayName("dispatch: ReferenceType 未対応 sourceType は visibility 通過 (fail-soft)")
    void dispatch_passes_through_unmapped_source_type() {
        // Given: MEMBER_PAYMENT は未マップ
        NotificationEntity notification = buildNotification("MEMBER_PAYMENT", CONTENT_ID);
        given(preferenceService.isNotificationEnabled(eq(USER_ID), any(), any())).willReturn(false);

        // When
        dispatchService.dispatch(notification);

        // Then: visibility は呼ばれず、preference に進む
        verify(visibilityChecker, never()).canView(any(), any(), any());
        verify(preferenceService).isNotificationEnabled(eq(USER_ID), any(), any());
    }

    @Test
    @DisplayName("dispatch: notification null は何もせず return")
    void dispatch_handles_null_notification() {
        // When
        dispatchService.dispatch(null);

        // Then
        verify(visibilityChecker, never()).canView(any(), any(), any());
        verify(preferenceService, never()).isNotificationEnabled(any(), any(), any());
    }

    @Test
    @DisplayName("dispatch: sourceId null は visibility 通過 (fail-soft)")
    void dispatch_passes_through_when_source_id_null() {
        // Given
        NotificationEntity notification = buildNotification("BLOG_POST", null);
        given(preferenceService.isNotificationEnabled(eq(USER_ID), any(), any())).willReturn(false);

        // When
        dispatchService.dispatch(notification);

        // Then
        verify(visibilityChecker, never()).canView(any(), any(), any());
        verify(preferenceService).isNotificationEnabled(eq(USER_ID), any(), any());
    }
}
