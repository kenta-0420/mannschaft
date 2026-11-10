package com.mannschaft.app.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.notification.dto.NotificationResponse;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.entity.PushSubscriptionEntity;
import com.mannschaft.app.notification.repository.NotificationPreferenceRepository;
import com.mannschaft.app.notification.repository.NotificationTypePreferenceRepository;
import com.mannschaft.app.notification.repository.PushSubscriptionRepository;
import com.mannschaft.app.notification.service.NotificationDispatchService;
import com.mannschaft.app.notification.service.NotificationPreferenceService;
import com.mannschaft.app.notification.service.PushSubscriptionService;
import com.mannschaft.app.notification.service.WebPushPayloadFactory;
import com.mannschaft.app.notification.service.WebPushService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link NotificationDispatchService} の Web Push ペイロード経路の試験（CMP-261029-0620）。
 *
 * <p>Push には {@link WebPushPayloadFactory} の JSON を送り、WebSocket には従来どおり
 * {@link NotificationResponse}（camelCase）を送ることを検査する（AC-5b/5c/5d・AC-16）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("NotificationDispatchService: Web Push ペイロード（CMP-261029-0620）")
class NotificationDispatchWebPushPayloadTest {

    private static final String PAYLOAD = "{\"title\":\"t\",\"body\":\"\",\"data\":{\"notification_id\":1,\"action_url\":null,\"priority\":\"NORMAL\"}}";

    @Mock private PushSubscriptionService pushSubscriptionService;
    @Mock private NotificationPreferenceService preferenceService;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private NotificationMapper notificationMapper;
    @Mock private WebPushService webPushService;
    @Mock private ObjectMapper objectMapper;
    @Mock private NotificationPreferenceRepository preferenceRepository;
    @Mock private NotificationTypePreferenceRepository typePreferenceRepository;
    @Mock private PushSubscriptionRepository pushSubscriptionRepository;
    @Mock private ContentVisibilityChecker visibilityChecker;
    @Mock private WebPushPayloadFactory webPushPayloadFactory;

    @InjectMocks
    private NotificationDispatchService dispatchService;

    @BeforeEach
    void setUp() {
        given(visibilityChecker.canView(any(ReferenceType.class), any(), any())).willReturn(true);
        given(preferenceService.isNotificationEnabled(anyLong(), anyString(), any())).willReturn(true);
        given(preferenceService.isTypeEnabled(anyLong(), anyString())).willReturn(true);
        given(webPushPayloadFactory.build(any())).willReturn(PAYLOAD);
    }

    private NotificationEntity notification(long userId) {
        return NotificationEntity.builder()
                .id(100L + userId)
                .userId(userId)
                .notificationType("SCHEDULE_REMINDER")
                .priority(NotificationPriority.NORMAL)
                .title("リマインド")
                .body("出欠未回答です")
                .sourceType("SCHEDULE")
                .sourceId(10L)
                .scopeType(NotificationScopeType.TEAM)
                .scopeId(5L)
                .actionUrl("/teams/x/schedules/10")
                .build();
    }

    private PushSubscriptionEntity subscription(long userId, String endpoint) {
        return PushSubscriptionEntity.builder()
                .userId(userId)
                .endpoint(endpoint)
                .p256dhKey("p")
                .authKey("a")
                .build();
    }

    @Test
    @DisplayName("AC-5b: 購読0件ならペイロードの生成も送信も0回")
    void ac5b_購読0件() {
        NotificationEntity n = notification(1L);
        given(pushSubscriptionService.listSubscriptions(1L)).willReturn(List.of());

        dispatchService.dispatch(n);

        verify(webPushPayloadFactory, never()).build(any());
        verify(webPushService, never()).sendPushNotification(any(), any());
    }

    @Test
    @DisplayName("AC-5c: 購読が複数でもペイロードの生成は1回、全購読へ同じ JSON を送る")
    void ac5c_購読複数でも生成1回() throws Exception {
        NotificationEntity n = notification(1L);
        PushSubscriptionEntity s1 = subscription(1L, "https://push.example/1");
        PushSubscriptionEntity s2 = subscription(1L, "https://push.example/2");
        given(pushSubscriptionService.listSubscriptions(1L)).willReturn(List.of(s1, s2));

        dispatchService.dispatch(n);

        verify(webPushPayloadFactory, times(1)).build(n);
        verify(webPushService).sendPushNotification(s1, PAYLOAD);
        verify(webPushService).sendPushNotification(s2, PAYLOAD);
        // NotificationResponse をそのまま Push に流す旧経路を使わない
        verify(objectMapper, never()).writeValueAsString(any());
    }

    @Test
    @DisplayName("AC-5d: チャンク配信は購読を一括先読みし（1クエリ）、受信者ごとの購読取得をしない")
    void ac5d_チャンク配信の一括先読み() {
        NotificationEntity n1 = notification(1L);
        NotificationEntity n2 = notification(2L);
        PushSubscriptionEntity s1 = subscription(1L, "https://push.example/1");
        PushSubscriptionEntity s2 = subscription(2L, "https://push.example/2");
        given(pushSubscriptionRepository.findByUserIdIn(any())).willReturn(List.of(s1, s2));
        given(preferenceRepository.findForDispatchBatch(any(), any(), any())).willReturn(List.of());
        given(typePreferenceRepository.findByUserIdInAndNotificationType(any(), any())).willReturn(List.of());

        dispatchService.dispatchBatch(List.of(n1, n2));

        verify(pushSubscriptionRepository, times(1)).findByUserIdIn(any());
        verify(preferenceRepository, times(1)).findForDispatchBatch(any(), any(), any());
        verify(typePreferenceRepository, times(1)).findByUserIdInAndNotificationType(any(), any());
        verify(pushSubscriptionService, never()).listSubscriptions(any());
        verify(webPushPayloadFactory, times(1)).build(n1);
        verify(webPushPayloadFactory, times(1)).build(n2);
        verify(webPushService).sendPushNotification(s1, PAYLOAD);
        verify(webPushService).sendPushNotification(s2, PAYLOAD);
    }

    @Test
    @DisplayName("AC-16: WebSocket には従来どおり NotificationResponse（camelCase の actionUrl）を送り、Push 用 JSON は流さない")
    void ac16_WebSocketはNotificationResponseのまま() {
        NotificationEntity n = notification(1L);
        NotificationResponse response = NotificationResponse.builder()
                .id(101L).userId(1L).actionUrl("/teams/x/schedules/10").build();
        given(notificationMapper.toNotificationResponse(n)).willReturn(response);
        given(pushSubscriptionService.listSubscriptions(1L))
                .willReturn(List.of(subscription(1L, "https://push.example/1")));

        dispatchService.dispatch(n);

        verify(messagingTemplate).convertAndSendToUser(eq("1"), eq("/queue/notifications"), eq(response));
        verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), eq(PAYLOAD));
        verify(webPushService).sendPushNotification(any(), eq(PAYLOAD));
    }
}
