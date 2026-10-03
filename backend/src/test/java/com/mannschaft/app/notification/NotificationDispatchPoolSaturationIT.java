package com.mannschaft.app.notification;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryResult;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #2953 — <b>プール飽和時に通知行が消えないこと</b>の実 DB 検証。
 *
 * <h2>なぜモックでは足りないのか</h2>
 * <p>本 IT が測るのは「{@code @Async} の投入拒否が {@code NotificationDeliveryRunner#sendOne} の
 * {@code REQUIRES_NEW} トランザクションを巻き戻し、作成済みの通知行ごと消す」という
 * <b>Spring のトランザクションの実挙動</b>である。{@code dispatch} をモックして例外を投げさせても
 * 「ロールバックが実際に起きるか」は再現できない。よって実物のスレッドプールを<b>本当に飽和させ</b>、
 * 実 DB（Testcontainers MySQL）に通知行が残るかを見る。</p>
 *
 * <h2>受け入れ条件との対応</h2>
 * <ul>
 *   <li>AC-1: {@code event-pool} が飽和していても通知行は消えない。
 *       <b>是正前はここが赤だった</b>（{@code dispatch} が {@code @Primary} の {@code event-pool} に
 *       自己投入され、拒否例外が {@code sendOne} を巻き戻して通知行が消える）。</li>
 *   <li>AC-2: 配送経路と {@code dispatch} が同じプールを奪い合わない
 *       （{@code event-pool} を使い切っていても {@code dispatch} は成立する）。</li>
 *   <li>AC-1（続き）: {@code notification-dispatch-pool} 自体が飽和しても、CallerRuns により
 *       例外にならず通知行は残る。</li>
 * </ul>
 *
 * <h2>クラスに {@code @Transactional} を付けない理由</h2>
 * <p>{@code sendOne} は {@code REQUIRES_NEW}。テストをトランザクションで包むと外側が最後に
 * ロールバックされ、実コミットの成否を測れない。フィクスチャ投入・検証読み取りは
 * {@link TransactionTemplate} で明示的にコミットする（{@code EventDismissalNotificationTransactionIT} と同型）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("Issue #2953 プール飽和時に通知行が消えないことの実DB検証")
class NotificationDispatchPoolSaturationIT extends AbstractMySqlIntegrationTest {

    /** {@code AsyncConfig#eventPoolExecutor} のキュー容量。 */
    private static final int EVENT_POOL_QUEUE_CAPACITY = 100;

    /** {@code AsyncConfig#notificationDispatchPool} のキュー容量。 */
    private static final int DISPATCH_POOL_QUEUE_CAPACITY = 500;

    @Autowired
    private NotificationDeliveryRunner notificationDeliveryRunner;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    @Qualifier("event-pool")
    private ThreadPoolTaskExecutor eventPool;

    @Autowired
    @Qualifier("notification-dispatch-pool")
    private ThreadPoolTaskExecutor notificationDispatchPool;

    @Test
    @DisplayName("AC-1/AC-2: event-pool を飽和させても通知行はコミットされて残る（自己投入の解消）")
    void イベントプール飽和時も通知行は残る() throws Exception {
        String nonce = String.valueOf(System.nanoTime());
        Long recipientId = insertUser("dispatch-saturation-event-" + nonce + "@example.com");

        CountDownLatch release = new CountDownLatch(1);
        try {
            saturate(eventPool, EVENT_POOL_QUEUE_CAPACITY, release);

            NotificationDeliveryResult result = notificationDeliveryRunner.sendOne(buildRequest(recipientId));

            assertThat(result)
                    .as("event-pool が使い切られていても配送は成立する（dispatch は別プール）")
                    .isEqualTo(NotificationDeliveryResult.DELIVERED);
        } finally {
            release.countDown();
        }

        assertThat(findNotifications(recipientId))
                .as("AC-1: プール飽和による投入拒否で通知行がロールバックして消えないこと")
                .isNotEmpty();
    }

    @Test
    @DisplayName("AC-1: notification-dispatch-pool 自体が飽和しても CallerRuns で通知行は残る")
    void 通知配信プール飽和時も通知行は残る() throws Exception {
        String nonce = String.valueOf(System.nanoTime());
        Long recipientId = insertUser("dispatch-saturation-dispatch-" + nonce + "@example.com");

        CountDownLatch release = new CountDownLatch(1);
        try {
            saturate(notificationDispatchPool, DISPATCH_POOL_QUEUE_CAPACITY, release);

            NotificationDeliveryResult result = notificationDeliveryRunner.sendOne(buildRequest(recipientId));

            assertThat(result)
                    .as("飽和時も拒否例外にならず（CallerRuns）配送が成立する")
                    .isEqualTo(NotificationDeliveryResult.DELIVERED);
        } finally {
            release.countDown();
        }

        assertThat(findNotifications(recipientId))
                .as("AC-1: CallerRuns により通知行は消えない")
                .isNotEmpty();
    }

    /**
     * 通知配送要求。{@code sourceType} は F00 の Resolver 未配備の値を用い、
     * visibility deny（null 復帰）で本筋がぼやけないようにする。
     *
     * @param recipientId 受信者ユーザーID
     * @return 通知配送要求
     */
    private NotificationDeliveryRequest buildRequest(Long recipientId) {
        return new NotificationDeliveryRequest(
                recipientId,
                "SYSTEM_ANNOUNCEMENT",
                NotificationPriority.NORMAL,
                "Issue #2953 飽和検証",
                "プール飽和時に通知行が消えないことを確認する",
                "CMP056_SATURATION_TEST",
                1L,
                NotificationScopeType.PERSONAL,
                recipientId,
                "/notifications",
                null);
    }

    private List<NotificationEntity> findNotifications(Long recipientId) {
        return transactionTemplate.execute(tx -> notificationRepository
                .findByUserIdOrderByCreatedAtDesc(recipientId, PageRequest.of(0, 10))
                .getContent());
    }

    /**
     * プールを「次の 1 件が必ず拒否される」状態まで詰める。
     *
     * <p>{@code maxPoolSize} 本のワーカーをラッチで塞ぎ、キューを容量いっぱいまで埋める。
     * 塞いだタスクは呼び出し側の {@code finally} でラッチを開放して必ず終わらせる。</p>
     *
     * @param executor      飽和させる対象プール
     * @param queueCapacity 対象プールのキュー容量
     * @param release       塞いだタスクを解放するラッチ
     * @throws InterruptedException 待機が割り込まれた場合
     */
    private void saturate(ThreadPoolTaskExecutor executor, int queueCapacity, CountDownLatch release)
            throws InterruptedException {
        int capacity = executor.getMaxPoolSize() + queueCapacity;
        CountDownLatch started = new CountDownLatch(executor.getMaxPoolSize());
        for (int i = 0; i < capacity; i++) {
            executor.execute(() -> {
                started.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        assertThat(started.await(10, TimeUnit.SECONDS))
                .as("全ワーカーが塞がって飽和状態になっていること（測定の前提）")
                .isTrue();
    }

    /**
     * ACTIVE な users 行を1件作成し id を返す。
     *
     * <p>実 DDL の NOT NULL 制約は test プロファイルの {@code ddl-auto: create} では再現せず
     * 実 DB で初めて落ちるため、{@code is_searchable} / {@code locale} / {@code timezone} を含む
     * 必須フィールドを明示する（{@code EventDismissalNotificationTransactionIT} と同じ注意点）。</p>
     *
     * @param email 一意なメールアドレス
     * @return 作成したユーザーID
     */
    private Long insertUser(String email) {
        return transactionTemplate.execute(tx -> userRepository.save(UserEntity.builder()
                .email(email)
                .lastName("飽和試験")
                .firstName("太郎")
                .displayName("飽和試験ユーザー" + UUID.randomUUID().toString().substring(0, 8))
                .isSearchable(true)
                .locale("ja")
                .timezone("Asia/Tokyo")
                .status(UserEntity.UserStatus.ACTIVE)
                .contactApprovalRequired(false)
                .build()).getId());
    }
}
