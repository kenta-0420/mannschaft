package com.mannschaft.app.notification.service;

import com.mannschaft.app.notification.config.VapidConfig;
import com.mannschaft.app.notification.entity.PushSubscriptionEntity;
import com.mannschaft.app.notification.repository.PushSubscriptionRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Subscription;
import nl.martijndwars.webpush.Subscription.Keys;
import org.apache.http.HttpResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Web Push（VAPID）送信サービス。
 *
 * <p>RFC8030 / Web Push Protocol に基づき、VAPID 署名付きの HTTP Push を
 * 各ブラウザプッシュサービス（FCM・APNs・Mozilla Push等）へ送信する。
 *
 * <p>レスポンスコード別の動作:
 * <ul>
 *   <li>201: 送信成功</li>
 *   <li>410/404: 購読が失効しているため DB から削除する</li>
 *   <li>429: Retry-After ヘッダに従って1回リトライする</li>
 *   <li>5xx/タイムアウト: 指数バックオフ（1s→4s→16s）で最大3回リトライ。
 *       上限超過はログ警告のみ（通知の欠落は許容する）</li>
 * </ul>
 *
 * <h2>所要時間の上限（Issue #2953 検分指摘1）</h2>
 * <p>本サービスは同期 HTTP + バックオフ sleep であり、
 * {@code notification-dispatch-pool} が飽和して CallerRuns が発火すると
 * {@code NotificationDeliveryRunner#sendOne} の {@code REQUIRES_NEW} トランザクションの
 * <b>内側</b>で、呼び出し元（{@code event-pool} ワーカー）スレッドにより同期実行される。
 * このとき Hikari コネクションと event-pool のワーカーを保持し続けるため、
 * <b>危険なのは接続の本数ではなく保持時間</b>である。そこで二段の上限を課す:</p>
 * <ul>
 *   <li><b>1 リクエストあたり</b>: {@link #REQUEST_TIMEOUT_MS}（既定 10 秒）。
 *       {@code PushService#send} は内部で {@code Future#get()} を無期限に待つため、
 *       {@code sendAsync} + {@code get(timeout)} に置き換えて上限を保証する。</li>
 *   <li><b>1 通知あたりの総所要</b>: {@link #TOTAL_BUDGET_MS}（既定 30 秒）。
 *       バックオフ sleep に入る前に残予算を確認し、尽きていればリトライせず諦める
 *       （例外は投げない。投げると CallerRuns 時に {@code REQUIRES_NEW} ごと
 *       巻き戻り通知行が消えるため）。</li>
 * </ul>
 * <p>したがって最悪所要は「総予算 + 実行中の 1 リクエスト分」で上に有界となる。
 * なお push の HTTP をトランザクション境界の外へ出す本筋の是正は別 issue（#2998）とする。</p>
 *
 * <p>設計書: {@code docs/features/F04.3_pwa_push_notification.md}
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebPushService {

    private static final int MAX_RETRY_COUNT = 3;
    private static long[] BACKOFF_DELAYS_MS = {1_000L, 4_000L, 16_000L};

    /**
     * 1 リクエスト（HTTP 往復）あたりのタイムアウト。
     * non-final なのはテストから短縮するため（{@link #BACKOFF_DELAYS_MS} と同じ流儀）。
     */
    static long REQUEST_TIMEOUT_MS = 10_000L;

    /**
     * 1 通知の送信（リトライ・バックオフ sleep を含む）に許す総予算。
     * 予算を使い切ったらリトライせず諦める（例外は投げない）。
     */
    static long TOTAL_BUDGET_MS = 30_000L;

    private final VapidConfig vapidConfig;
    private final PushSubscriptionRepository pushSubscriptionRepository;

    private PushService pushService;

    /**
     * VAPID鍵が設定されている場合に PushService を初期化する。
     * 鍵が未設定の場合は初期化をスキップし、送信時に警告を出す（開発環境用）。
     */
    @PostConstruct
    void init() {
        String publicKey = vapidConfig.getPublicKey();
        String privateKey = vapidConfig.getPrivateKey();
        if (StringUtils.hasText(publicKey) && StringUtils.hasText(privateKey)) {
            try {
                pushService = new PushService(publicKey, privateKey);
                log.info("WebPushService: VAPID鍵を読み込みました（公開鍵の先頭10文字={}...）",
                        publicKey.substring(0, Math.min(10, publicKey.length())));
            } catch (Exception e) {
                log.error("WebPushService: VAPID鍵の初期化に失敗しました。Web Push は無効化されます。error={}",
                        e.getMessage(), e);
            }
        } else {
            log.warn("WebPushService: VAPID_PUBLIC_KEY / VAPID_PRIVATE_KEY が未設定です。" +
                    "Web Push は送信されません（開発環境）。");
        }
    }

    /**
     * 指定した購読先へ Web Push 通知を送信する。
     *
     * <p>VAPID 鍵未設定の場合は送信をスキップし、ログ警告のみ出力する。
     *
     * @param subscription 送信先購読エンティティ
     * @param jsonPayload  送信する JSON ペイロード文字列（通知タイトル・本文等）
     */
    public void sendPushNotification(PushSubscriptionEntity subscription, String jsonPayload) {
        if (pushService == null) {
            log.debug("VAPID未設定のためWebPushをスキップ: endpoint={}",
                    abbreviateEndpoint(subscription.getEndpoint()));
            return;
        }

        String endpoint = subscription.getEndpoint();
        int retryCount = 0;
        final long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TOTAL_BUDGET_MS);

        while (retryCount <= MAX_RETRY_COUNT) {
            try {
                int statusCode = doSend(subscription, jsonPayload);

                if (statusCode == 201) {
                    log.debug("WebPush送信成功: endpoint={}", abbreviateEndpoint(endpoint));
                    return;

                } else if (statusCode == 410 || statusCode == 404) {
                    log.info("WebPush購読失効（{}）: endpoint={} → DBから削除", statusCode, abbreviateEndpoint(endpoint));
                    pushSubscriptionRepository.deleteByEndpoint(endpoint);
                    return;

                } else if (statusCode == 429) {
                    if (retryCount < 1) {
                        log.warn("WebPushレート制限（429）: endpoint={}, 1回リトライします", abbreviateEndpoint(endpoint));
                        if (!awaitBeforeRetry(BACKOFF_DELAYS_MS[0], deadlineNanos, endpoint)) {
                            return;
                        }
                        retryCount++;
                    } else {
                        log.warn("WebPushレート制限（429）: リトライ上限到達、スキップします。endpoint={}",
                                abbreviateEndpoint(endpoint));
                        return;
                    }

                } else if (statusCode >= 500) {
                    if (retryCount < MAX_RETRY_COUNT) {
                        long delay = BACKOFF_DELAYS_MS[Math.min(retryCount, BACKOFF_DELAYS_MS.length - 1)];
                        log.warn("WebPushサーバーエラー（{}）: {}ms後にリトライ({}/{})。endpoint={}",
                                statusCode, delay, retryCount + 1, MAX_RETRY_COUNT, abbreviateEndpoint(endpoint));
                        if (!awaitBeforeRetry(delay, deadlineNanos, endpoint)) {
                            return;
                        }
                        retryCount++;
                    } else {
                        log.warn("WebPushサーバーエラー（{}）: リトライ上限到達、スキップします。endpoint={}",
                                statusCode, abbreviateEndpoint(endpoint));
                        return;
                    }

                } else {
                    log.warn("WebPush予期しないレスポンス（{}）: endpoint={}", statusCode, abbreviateEndpoint(endpoint));
                    return;
                }

            } catch (Exception e) {
                if (retryCount < MAX_RETRY_COUNT) {
                    long delay = BACKOFF_DELAYS_MS[Math.min(retryCount, BACKOFF_DELAYS_MS.length - 1)];
                    log.warn("WebPush送信例外: {}ms後にリトライ({}/{})。endpoint={}, error={}",
                            delay, retryCount + 1, MAX_RETRY_COUNT, abbreviateEndpoint(endpoint), e.getMessage());
                    if (!awaitBeforeRetry(delay, deadlineNanos, endpoint)) {
                        return;
                    }
                    retryCount++;
                } else {
                    log.warn("WebPush送信失敗（例外）: リトライ上限到達、スキップします。endpoint={}, error={}",
                            abbreviateEndpoint(endpoint), e.getMessage());
                    return;
                }
            }
        }
    }

    /**
     * 実際の HTTP Push 送信を行い、レスポンスのステータスコードを返す。
     * package-private: テストで Mockito spy による差し替えを許容するために可視性を緩める。
     */
    int doSend(PushSubscriptionEntity subscription, String jsonPayload) throws Exception {
        Keys keys = new Keys(subscription.getP256dhKey(), subscription.getAuthKey());
        Subscription webPushSubscription = new Subscription(subscription.getEndpoint(), keys);
        Notification notification = new Notification(webPushSubscription, jsonPayload);
        // PushService#send は内部で Future#get() を無期限に待つため使わない。
        // sendAsync + get(timeout) で 1 リクエストの所要時間を必ず有界にする。
        Future<HttpResponse> future = pushService.sendAsync(notification);
        try {
            HttpResponse response = future.get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return response.getStatusLine().getStatusCode();
        } catch (TimeoutException te) {
            future.cancel(true);
            // 呼び出し元の catch(Exception) がバックオフ・リトライ経路として拾う。
            throw te;
        }
    }

    /**
     * バックオフ sleep に入る前に総予算の残りを確認し、足りていれば sleep する。
     *
     * @return リトライを続けてよいなら true、予算を使い切ったので諦めるなら false
     */
    private boolean awaitBeforeRetry(long delayMs, long deadlineNanos, String endpoint) {
        long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
        if (remainingMs <= 0 || remainingMs < delayMs) {
            log.warn("WebPush送信の総所要時間の上限（{}ms）に到達したためリトライを打ち切ります。endpoint={}",
                    TOTAL_BUDGET_MS, abbreviateEndpoint(endpoint));
            return false;
        }
        sleepSilently(delayMs);
        return true;
    }

    /**
     * エンドポイントURLを先頭50文字に省略してログ用文字列を返す。
     */
    private String abbreviateEndpoint(String endpoint) {
        if (endpoint == null) {
            return "(null)";
        }
        int limit = Math.min(50, endpoint.length());
        return endpoint.substring(0, limit) + (endpoint.length() > 50 ? "..." : "");
    }

    /**
     * InterruptedException を飲み込む sleep ラッパー（配信は best-effort）。
     */
    private void sleepSilently(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
