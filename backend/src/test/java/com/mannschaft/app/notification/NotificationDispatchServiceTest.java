package com.mannschaft.app.notification;

import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.notification.dto.NotificationResponse;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.entity.PushSubscriptionEntity;
import com.mannschaft.app.notification.service.NotificationDispatchService;
import com.mannschaft.app.notification.service.NotificationPreferenceService;
import com.mannschaft.app.notification.service.PushSubscriptionService;
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
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link NotificationDispatchService} の単体テスト。
 * WebSocket・PWA Push配信の振り分けロジックを検証する。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("NotificationDispatchService 単体テスト")
class NotificationDispatchServiceTest {

    @Mock
    private PushSubscriptionService pushSubscriptionService;

    @Mock
    private NotificationPreferenceService preferenceService;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private NotificationMapper notificationMapper;

    /**
     * F00 Phase F セキュリティガード用の visibility checker (mock)。
     * 既存テストは「visibility は通過した前提」で各シナリオを検証するため、
     * デフォルトで {@code canView} を true にスタブする。
     */
    @Mock
    private ContentVisibilityChecker visibilityChecker;

    @InjectMocks
    private NotificationDispatchService dispatchService;

    @BeforeEach
    void setUpVisibilityCheckerDefaults() {
        // F00 Phase F: 既存テストの sourceType=SCHEDULE は ReferenceType.SCHEDULE
        // に解決され canView が呼ばれる。配信ロジックの個別検証 (本テストの主旨)
        // を可能にするため、デフォルトで全 user が閲覧可とする。
        given(visibilityChecker.canView(any(ReferenceType.class), any(), any())).willReturn(true);
    }

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long USER_ID = 1L;

    private NotificationEntity createNotification() {
        return NotificationEntity.builder()
                .userId(USER_ID)
                .notificationType("SCHEDULE_REMINDER")
                .priority(NotificationPriority.NORMAL)
                .title("リマインド")
                .body("出欠未回答です")
                .sourceType("SCHEDULE")
                .sourceId(10L)
                .scopeType(NotificationScopeType.TEAM)
                .scopeId(5L)
                .actionUrl("/schedules/10")
                .actorId(2L)
                .build();
    }

    private NotificationResponse createNotificationResponse() {
        return new NotificationResponse(
                100L, USER_ID, "SCHEDULE_REMINDER", "NORMAL",
                "リマインド", "出欠未回答です", "SCHEDULE", 10L,
                "TEAM", 5L, "/schedules/10", 2L,
                false, null, null, null, LocalDateTime.now()
        );
    }

    private PushSubscriptionEntity createSubscription() {
        return PushSubscriptionEntity.builder()
                .userId(USER_ID)
                .endpoint("https://fcm.googleapis.com/fcm/send/test-endpoint")
                .p256dhKey("test-p256dh")
                .authKey("test-auth")
                .userAgent("Mozilla/5.0")
                .build();
    }

    // ========================================
    // dispatch
    // ========================================

    @Nested
    @DisplayName("dispatch")
    class Dispatch {

        @Test
        @DisplayName("配信_全設定有効_WebSocketとPush両方送信")
        void 配信_全設定有効_WebSocketとPush両方送信() {
            // Given
            NotificationEntity notification = createNotification();
            NotificationResponse response = createNotificationResponse();
            PushSubscriptionEntity subscription = createSubscription();

            given(preferenceService.isNotificationEnabled(USER_ID, "TEAM", 5L)).willReturn(true);
            given(preferenceService.isTypeEnabled(USER_ID, "SCHEDULE_REMINDER")).willReturn(true);
            given(notificationMapper.toNotificationResponse(notification)).willReturn(response);
            given(pushSubscriptionService.listSubscriptions(USER_ID)).willReturn(List.of(subscription));

            // When
            dispatchService.dispatch(notification);

            // Then
            verify(messagingTemplate).convertAndSendToUser(
                    eq(USER_ID.toString()),
                    eq("/queue/notifications"),
                    eq(response));
            verify(pushSubscriptionService).listSubscriptions(USER_ID);
        }

        @Test
        @DisplayName("配信_スコープ無効_送信スキップ")
        void 配信_スコープ無効_送信スキップ() {
            // Given
            NotificationEntity notification = createNotification();

            given(preferenceService.isNotificationEnabled(USER_ID, "TEAM", 5L)).willReturn(false);

            // When
            dispatchService.dispatch(notification);

            // Then
            verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
            verify(pushSubscriptionService, never()).listSubscriptions(any());
        }

        @Test
        @DisplayName("配信_種別無効_送信スキップ")
        void 配信_種別無効_送信スキップ() {
            // Given
            NotificationEntity notification = createNotification();

            given(preferenceService.isNotificationEnabled(USER_ID, "TEAM", 5L)).willReturn(true);
            given(preferenceService.isTypeEnabled(USER_ID, "SCHEDULE_REMINDER")).willReturn(false);

            // When
            dispatchService.dispatch(notification);

            // Then
            verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
            verify(pushSubscriptionService, never()).listSubscriptions(any());
        }

        @Test
        @DisplayName("配信_プッシュ購読なし_WebSocketのみ送信")
        void 配信_プッシュ購読なし_WebSocketのみ送信() {
            // Given
            NotificationEntity notification = createNotification();
            NotificationResponse response = createNotificationResponse();

            given(preferenceService.isNotificationEnabled(USER_ID, "TEAM", 5L)).willReturn(true);
            given(preferenceService.isTypeEnabled(USER_ID, "SCHEDULE_REMINDER")).willReturn(true);
            given(notificationMapper.toNotificationResponse(notification)).willReturn(response);
            given(pushSubscriptionService.listSubscriptions(USER_ID)).willReturn(List.of());

            // When
            dispatchService.dispatch(notification);

            // Then
            verify(messagingTemplate).convertAndSendToUser(
                    eq(USER_ID.toString()),
                    eq("/queue/notifications"),
                    eq(response));
        }

        @Test
        @DisplayName("配信_WebSocket送信失敗_例外を握りつぶして継続")
        void 配信_WebSocket送信失敗_例外を握りつぶして継続() {
            // Given
            NotificationEntity notification = createNotification();

            given(preferenceService.isNotificationEnabled(USER_ID, "TEAM", 5L)).willReturn(true);
            given(preferenceService.isTypeEnabled(USER_ID, "SCHEDULE_REMINDER")).willReturn(true);
            given(notificationMapper.toNotificationResponse(notification))
                    .willThrow(new RuntimeException("WebSocket接続エラー"));
            given(pushSubscriptionService.listSubscriptions(USER_ID)).willReturn(List.of());

            // When - 例外がスローされずに完了すること
            dispatchService.dispatch(notification);

            // Then - Push送信処理まで到達していること
            verify(pushSubscriptionService).listSubscriptions(USER_ID);
        }
    }
}
