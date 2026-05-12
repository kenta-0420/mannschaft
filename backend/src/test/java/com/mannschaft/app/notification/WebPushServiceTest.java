package com.mannschaft.app.notification;

import com.mannschaft.app.notification.config.VapidConfig;
import com.mannschaft.app.notification.entity.PushSubscriptionEntity;
import com.mannschaft.app.notification.repository.PushSubscriptionRepository;
import com.mannschaft.app.notification.service.WebPushService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * {@link WebPushService} の単体テスト。
 * doSend を Mockito spy で差し替え、Notification 生成の EC 暗号依存を排除して
 * ステータスコード別の分岐ロジック・リトライ・削除処理を検証する。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WebPushService 単体テスト")
class WebPushServiceTest {

    @Mock
    private VapidConfig vapidConfig;

    @Mock
    private PushSubscriptionRepository pushSubscriptionRepository;

    private WebPushService webPushService;

    private PushSubscriptionEntity buildSubscription() {
        return PushSubscriptionEntity.builder()
                .userId(1L)
                .endpoint("https://fcm.googleapis.com/fcm/send/test-endpoint-123456789")
                .p256dhKey("dummy-p256dh")
                .authKey("dummy-auth")
                .build();
    }

    @BeforeEach
    void setUp() {
        WebPushService base = new WebPushService(vapidConfig, pushSubscriptionRepository);
        // pushService に非 null を設定して「VAPID 設定済み」状態にする
        ReflectionTestUtils.setField(base, "pushService", new Object());
        // spy で doSend を差し替え可能にする（EC 暗号依存を排除）
        webPushService = spy(base);
    }

    @Nested
    @DisplayName("201 成功ケース")
    class SuccessCase {

        @Test
        @DisplayName("201 が返れば送信成功としてそのまま終了する")
        void sendPushNotification_returns201_success() throws Exception {
            doReturn(201).when(webPushService).doSend(any(PushSubscriptionEntity.class), anyString());
            PushSubscriptionEntity subscription = buildSubscription();

            webPushService.sendPushNotification(subscription, "{\"type\":\"TEST\"}");

            verify(webPushService, times(1)).doSend(any(PushSubscriptionEntity.class), anyString());
            verify(pushSubscriptionRepository, never()).deleteByEndpoint(any());
        }
    }

    @Nested
    @DisplayName("410/404 購読失効ケース")
    class ExpiredSubscriptionCase {

        @Test
        @DisplayName("410 が返れば購読を DB から削除する")
        void sendPushNotification_returns410_deletesSubscription() throws Exception {
            PushSubscriptionEntity subscription = buildSubscription();
            doReturn(410).when(webPushService).doSend(any(PushSubscriptionEntity.class), anyString());

            webPushService.sendPushNotification(subscription, "{\"type\":\"TEST\"}");

            verify(webPushService, times(1)).doSend(any(PushSubscriptionEntity.class), anyString());
            verify(pushSubscriptionRepository).deleteByEndpoint(subscription.getEndpoint());
        }

        @Test
        @DisplayName("404 が返れば購読を DB から削除する")
        void sendPushNotification_returns404_deletesSubscription() throws Exception {
            PushSubscriptionEntity subscription = buildSubscription();
            doReturn(404).when(webPushService).doSend(any(PushSubscriptionEntity.class), anyString());

            webPushService.sendPushNotification(subscription, "{\"type\":\"TEST\"}");

            verify(pushSubscriptionRepository).deleteByEndpoint(subscription.getEndpoint());
        }
    }

    @Nested
    @DisplayName("リトライケース")
    class RetryCase {

        @Test
        @DisplayName("5xx が返れば最大3回リトライしてすべて失敗したら諦める")
        void sendPushNotification_returns500_retriesAndGivesUp() throws Exception {
            doReturn(500).when(webPushService).doSend(any(PushSubscriptionEntity.class), anyString());
            // BACKOFF_DELAYS_MS は non-final なので setField で置換可能
            ReflectionTestUtils.setField(WebPushService.class, "BACKOFF_DELAYS_MS", new long[]{0L, 0L, 0L});

            webPushService.sendPushNotification(buildSubscription(), "{\"type\":\"TEST\"}");

            // 初回 + 最大3回リトライ = 合計4回
            verify(webPushService, times(4)).doSend(any(PushSubscriptionEntity.class), anyString());
            verify(pushSubscriptionRepository, never()).deleteByEndpoint(any());
        }

        @Test
        @DisplayName("例外が発生した場合もリトライして最終的に諦める")
        void sendPushNotification_throwsException_retriesAndGivesUp() throws Exception {
            doThrow(new RuntimeException("接続タイムアウト"))
                    .when(webPushService).doSend(any(PushSubscriptionEntity.class), anyString());
            ReflectionTestUtils.setField(WebPushService.class, "BACKOFF_DELAYS_MS", new long[]{0L, 0L, 0L});

            // 例外が外に漏れないことも確認
            webPushService.sendPushNotification(buildSubscription(), "{\"type\":\"TEST\"}");

            // 初回 + 最大3回リトライ = 合計4回
            verify(webPushService, times(4)).doSend(any(PushSubscriptionEntity.class), anyString());
        }
    }

    @Nested
    @DisplayName("VAPID未設定ケース")
    class VapidNotConfiguredCase {

        @Test
        @DisplayName("pushService が null の場合はスキップして doSend を呼ばない")
        void sendPushNotification_pushServiceNull_skips() {
            ReflectionTestUtils.setField(webPushService, "pushService", null);
            PushSubscriptionEntity subscription = buildSubscription();

            webPushService.sendPushNotification(subscription, "{\"type\":\"TEST\"}");

            verify(webPushService, never()).doSend(any(PushSubscriptionEntity.class), anyString());
            verifyNoInteractions(pushSubscriptionRepository);
        }
    }
}
