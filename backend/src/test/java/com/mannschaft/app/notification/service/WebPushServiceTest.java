package com.mannschaft.app.notification.service;

import com.mannschaft.app.notification.config.VapidConfig;
import com.mannschaft.app.notification.entity.PushSubscriptionEntity;
import com.mannschaft.app.notification.repository.PushSubscriptionRepository;
import nl.martijndwars.webpush.PushService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
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

    @Mock
    private PushService pushService;

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
        // pushService に mock を注入して「VAPID 設定済み」状態にする（型一致が必要）
        ReflectionTestUtils.setField(base, "pushService", pushService);
        // spy で doSend を差し替え可能にする（EC 暗号依存を排除）
        webPushService = spy(base);
    }

    @AfterEach
    void restoreTimeoutConstants() {
        // 所要時間の上限は static なのでテスト間に漏れないよう既定値へ戻す
        ReflectionTestUtils.setField(WebPushService.class, "REQUEST_TIMEOUT_MS", 10_000L);
        ReflectionTestUtils.setField(WebPushService.class, "TOTAL_BUDGET_MS", 30_000L);
        ReflectionTestUtils.setField(WebPushService.class, "BACKOFF_DELAYS_MS", new long[]{1_000L, 4_000L, 16_000L});
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
    @DisplayName("所要時間の上限ケース（Issue #2953 検分指摘1）")
    class TimeBudgetCase {

        /**
         * 総予算を使い切った場合、<b>例外を投げずに</b>リトライを打ち切る。
         * 例外を投げると CallerRuns 時に sendOne の REQUIRES_NEW ごと巻き戻り
         * 通知行が消えるため、「諦めて次へ」でなければならない。
         */
        @Test
        @DisplayName("総予算を使い切ったらリトライせず例外も投げずに諦める")
        void sendPushNotification_totalBudgetExhausted_stopsRetryingWithoutThrowing() throws Exception {
            doReturn(500).when(webPushService).doSend(any(PushSubscriptionEntity.class), anyString());
            // バックオフ待ち（1s）に対して総予算 0ms → 1 回目のリトライ前に打ち切られる
            ReflectionTestUtils.setField(WebPushService.class, "BACKOFF_DELAYS_MS", new long[]{1_000L, 4_000L, 16_000L});
            ReflectionTestUtils.setField(WebPushService.class, "TOTAL_BUDGET_MS", 0L);

            long startNanos = System.nanoTime();
            Assertions.assertDoesNotThrow(
                    () -> webPushService.sendPushNotification(buildSubscription(), "{\"type\":\"TEST\"}"));
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

            // 初回のみ。バックオフ sleep には入らない
            verify(webPushService, times(1)).doSend(any(PushSubscriptionEntity.class), anyString());
            Assertions.assertTrue(elapsedMs < 1_000L,
                    "総予算超過時はバックオフ sleep に入らないはずだが " + elapsedMs + "ms かかった");
        }

        /**
         * 1 リクエストのタイムアウト（doSend が TimeoutException を投げる）は
         * 既存の例外リトライ経路として扱われ、最終的に例外を外へ漏らさず諦める。
         */
        @Test
        @DisplayName("リクエストタイムアウトはリトライ経路として扱われ例外を外へ漏らさない")
        void sendPushNotification_requestTimeout_retriesAndGivesUpWithoutThrowing() throws Exception {
            doThrow(new java.util.concurrent.TimeoutException("request timeout"))
                    .when(webPushService).doSend(any(PushSubscriptionEntity.class), anyString());
            ReflectionTestUtils.setField(WebPushService.class, "BACKOFF_DELAYS_MS", new long[]{0L, 0L, 0L});

            Assertions.assertDoesNotThrow(
                    () -> webPushService.sendPushNotification(buildSubscription(), "{\"type\":\"TEST\"}"));

            // 初回 + 最大3回リトライ = 合計4回
            verify(webPushService, times(4)).doSend(any(PushSubscriptionEntity.class), anyString());
        }
    }

    @Nested
    @DisplayName("VAPID未設定ケース")
    class VapidNotConfiguredCase {

        @Test
        @DisplayName("pushService が null の場合はスキップして doSend を呼ばない")
        void sendPushNotification_pushServiceNull_skips() throws Exception {
            ReflectionTestUtils.setField(webPushService, "pushService", null);
            PushSubscriptionEntity subscription = buildSubscription();

            webPushService.sendPushNotification(subscription, "{\"type\":\"TEST\"}");

            verify(webPushService, never()).doSend(any(PushSubscriptionEntity.class), anyString());
            verifyNoInteractions(pushSubscriptionRepository);
        }
    }
}
