package com.mannschaft.app.notification;

import com.mannschaft.app.notification.config.VapidConfig;
import com.mannschaft.app.notification.entity.PushSubscriptionEntity;
import com.mannschaft.app.notification.repository.PushSubscriptionRepository;
import com.mannschaft.app.notification.service.WebPushService;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.apache.http.HttpResponse;
import org.apache.http.ProtocolVersion;
import org.apache.http.StatusLine;
import org.apache.http.message.BasicStatusLine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * {@link WebPushService} の単体テスト。
 * PushService をモック化して HTTP Push 送信ロジックを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WebPushService 単体テスト")
class WebPushServiceTest {

    @Mock
    private VapidConfig vapidConfig;

    @Mock
    private PushSubscriptionRepository pushSubscriptionRepository;

    @Mock
    private PushService mockPushService;

    @Mock
    private HttpResponse mockHttpResponse;

    private WebPushService webPushService;

    private PushSubscriptionEntity buildSubscription() {
        return PushSubscriptionEntity.builder()
                .userId(1L)
                .endpoint("https://fcm.googleapis.com/fcm/send/test-endpoint-123456789")
                .p256dhKey("BPaKJbCK3lfRqP256dhKeyBase64EncodedDummyValue==")
                .authKey("authKeyBase64EncodedDummy==")
                .build();
    }

    @BeforeEach
    void setUp() {
        webPushService = new WebPushService(vapidConfig, pushSubscriptionRepository);
        // PushService のモックを直接注入（@PostConstruct をバイパス）
        ReflectionTestUtils.setField(webPushService, "pushService", mockPushService);
    }

    @Nested
    @DisplayName("201 成功ケース")
    class SuccessCase {

        @Test
        @DisplayName("201 が返れば送信成功としてそのまま終了する")
        void sendPushNotification_returns201_success() throws Exception {
            // Arrange
            StatusLine statusLine = new BasicStatusLine(new ProtocolVersion("HTTP", 1, 1), 201, "Created");
            given(mockHttpResponse.getStatusLine()).willReturn(statusLine);
            given(mockPushService.send(any(Notification.class))).willReturn(mockHttpResponse);

            PushSubscriptionEntity subscription = buildSubscription();

            // Act
            webPushService.sendPushNotification(subscription, "{\"type\":\"TEST\"}");

            // Assert
            verify(mockPushService, times(1)).send(any(Notification.class));
            verify(pushSubscriptionRepository, never()).deleteByEndpoint(any());
        }
    }

    @Nested
    @DisplayName("410/404 購読失効ケース")
    class ExpiredSubscriptionCase {

        @Test
        @DisplayName("410 が返れば購読を DB から削除する")
        void sendPushNotification_returns410_deletesSubscription() throws Exception {
            // Arrange
            StatusLine statusLine = new BasicStatusLine(new ProtocolVersion("HTTP", 1, 1), 410, "Gone");
            given(mockHttpResponse.getStatusLine()).willReturn(statusLine);
            given(mockPushService.send(any(Notification.class))).willReturn(mockHttpResponse);

            PushSubscriptionEntity subscription = buildSubscription();

            // Act
            webPushService.sendPushNotification(subscription, "{\"type\":\"TEST\"}");

            // Assert
            verify(mockPushService, times(1)).send(any(Notification.class));
            ArgumentCaptor<String> endpointCaptor = ArgumentCaptor.forClass(String.class);
            verify(pushSubscriptionRepository).deleteByEndpoint(endpointCaptor.capture());
            assert endpointCaptor.getValue().equals(subscription.getEndpoint());
        }

        @Test
        @DisplayName("404 が返れば購読を DB から削除する")
        void sendPushNotification_returns404_deletesSubscription() throws Exception {
            // Arrange
            StatusLine statusLine = new BasicStatusLine(new ProtocolVersion("HTTP", 1, 1), 404, "Not Found");
            given(mockHttpResponse.getStatusLine()).willReturn(statusLine);
            given(mockPushService.send(any(Notification.class))).willReturn(mockHttpResponse);

            PushSubscriptionEntity subscription = buildSubscription();

            // Act
            webPushService.sendPushNotification(subscription, "{\"type\":\"TEST\"}");

            // Assert
            verify(pushSubscriptionRepository).deleteByEndpoint(subscription.getEndpoint());
        }
    }

    @Nested
    @DisplayName("リトライケース")
    class RetryCase {

        @Test
        @DisplayName("5xx が返れば最大3回リトライしてすべて失敗したら諦める")
        void sendPushNotification_returns500_retriesAndGivesUp() throws Exception {
            // Arrange
            StatusLine statusLine500 = new BasicStatusLine(new ProtocolVersion("HTTP", 1, 1), 500, "Internal Server Error");
            given(mockHttpResponse.getStatusLine()).willReturn(statusLine500);
            // send を何度呼んでも 500 を返す
            given(mockPushService.send(any(Notification.class))).willReturn(mockHttpResponse);

            PushSubscriptionEntity subscription = buildSubscription();

            // WebPushService の BACKOFF_DELAYS_MS を 0 に置き換えてテストを高速化
            ReflectionTestUtils.setField(WebPushService.class, "BACKOFF_DELAYS_MS",
                    new long[]{0L, 0L, 0L});

            // Act
            webPushService.sendPushNotification(subscription, "{\"type\":\"TEST\"}");

            // Assert: 初回 + 最大3回リトライ = 合計4回
            verify(mockPushService, times(4)).send(any(Notification.class));
            verify(pushSubscriptionRepository, never()).deleteByEndpoint(any());
        }

        @Test
        @DisplayName("例外が発生した場合もリトライして最終的に諦める")
        void sendPushNotification_throwsException_retriesAndGivesUp() throws Exception {
            // Arrange
            given(mockPushService.send(any(Notification.class)))
                    .willThrow(new RuntimeException("接続タイムアウト"));

            PushSubscriptionEntity subscription = buildSubscription();

            ReflectionTestUtils.setField(WebPushService.class, "BACKOFF_DELAYS_MS",
                    new long[]{0L, 0L, 0L});

            // Act（例外が外に漏れないことも確認）
            webPushService.sendPushNotification(subscription, "{\"type\":\"TEST\"}");

            // Assert: 初回 + 最大3回リトライ = 合計4回
            verify(mockPushService, times(4)).send(any(Notification.class));
        }
    }

    @Nested
    @DisplayName("VAPID未設定ケース")
    class VapidNotConfiguredCase {

        @Test
        @DisplayName("pushService が null の場合はスキップして PushService を呼ばない")
        void sendPushNotification_pushServiceNull_skips() {
            // Arrange: pushService を null に設定
            ReflectionTestUtils.setField(webPushService, "pushService", null);
            PushSubscriptionEntity subscription = buildSubscription();

            // Act
            webPushService.sendPushNotification(subscription, "{\"type\":\"TEST\"}");

            // Assert
            verifyNoInteractions(mockPushService);
            verifyNoInteractions(pushSubscriptionRepository);
        }
    }
}
