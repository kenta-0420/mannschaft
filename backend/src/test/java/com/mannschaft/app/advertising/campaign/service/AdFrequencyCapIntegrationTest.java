package com.mannschaft.app.advertising.campaign.service;

import com.mannschaft.app.advertising.campaign.entity.UserAdDeliveryCounter;
import com.mannschaft.app.advertising.campaign.repository.UserAdDeliveryCounterRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AdFrequencyCapService} の Testcontainers Redis 統合テスト。
 *
 * <p>{@code @SpringBootTest} は使わず（{@code AbstractMySqlIntegrationTest} の
 * TestContext Cache 分裂を避けるため）、Lettuce + StringRedisTemplate を
 * テストで直接組み立てる。Redis は {@code redis:7-alpine} を Testcontainers で起動する。</p>
 *
 * <p>flush バッチ部分は RDB が必要なため、本ファイルでは Valkey 周りに限定して検証する。
 * RDB を含むフルパス検証は別途 {@code @SpringBootTest} 統合テストで扱う想定。</p>
 */
@DisplayName("AdFrequencyCapService 統合テスト（Testcontainers Redis）")
@EnabledIf("com.mannschaft.app.advertising.campaign.service.AdFrequencyCapIntegrationTest#isDockerAvailable")
class AdFrequencyCapIntegrationTest {

    private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7-alpine");

    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS = new GenericContainer<>(REDIS_IMAGE)
            .withExposedPorts(6379)
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(120)));

    private static StringRedisTemplate redisTemplate;
    private static LettuceConnectionFactory connectionFactory;

    private AdFrequencyCapService service;
    private AdFrequencyCapConfig config;
    private UserRepository userRepository;
    private UserAdDeliveryCounterRepository counterRepository;
    private AdFrequencyCapFlushBatch flushBatch;

    public static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            return false;
        }
    }

    @BeforeAll
    static void startContainer() {
        if (!isDockerAvailable()) {
            return;
        }
        try {
            REDIS.start();
        } catch (Exception e) {
            // Docker は存在するがコンテナ起動失敗（リソース枯渇・ネットワーク問題等）はスキップ扱い
            org.junit.jupiter.api.Assumptions.abort("Redisコンテナ起動失敗（環境問題）: " + e.getMessage());
        }
        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(
                REDIS.getHost(), REDIS.getFirstMappedPort());
        connectionFactory = new LettuceConnectionFactory(standalone);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
    }

    @AfterAll
    static void stopContainer() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
        if (REDIS.isRunning()) {
            REDIS.stop();
        }
    }

    @BeforeEach
    void setUp() {
        // 各テスト前にキー全削除
        if (connectionFactory != null) {
            connectionFactory.getConnection().serverCommands().flushAll();
        }
        config = new AdFrequencyCapConfig();
        config.setWeeklyTotal(3);
        config.setWeeklyPerAdvertiser(1);

        userRepository = mock(UserRepository.class);
        lenient().when(userRepository.findTimezoneById(anyLong())).thenReturn(Optional.of("Asia/Tokyo"));

        service = new AdFrequencyCapService(redisTemplate, userRepository, config);

        counterRepository = mock(UserAdDeliveryCounterRepository.class);
        // upsert 用: 常に「未存在」を返してテストでは新規作成として扱う
        lenient().when(counterRepository.findByUserIdAndWeekStartDate(anyLong(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.empty());
        lenient().when(counterRepository.save(org.mockito.ArgumentMatchers.any(UserAdDeliveryCounter.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        flushBatch = new AdFrequencyCapFlushBatch(redisTemplate, counterRepository);
    }

    @Test
    @DisplayName("実 Valkey: 3 件 consume 成功・4 件目で false（複数広告主）")
    void 実Valkey_3件成功_4件目失敗() {
        Long userId = 5001L;
        // 個人合計 3 件まで OK にするため、3 つの異なる広告主を使う
        Long adv1 = 1L;
        Long adv2 = 2L;
        Long adv3 = 3L;

        assertThat(service.tryConsume(userId, adv1, UUID.randomUUID())).isTrue();
        assertThat(service.tryConsume(userId, adv2, UUID.randomUUID())).isTrue();
        assertThat(service.tryConsume(userId, adv3, UUID.randomUUID())).isTrue();

        // 4 件目（個人合計上限超過）
        Long adv4 = 4L;
        assertThat(service.tryConsume(userId, adv4, UUID.randomUUID())).isFalse();

        // Valkey 上の個人合計カウンタは 3 で確定（ロールバック済み）
        LocalDate weekStart = AdFrequencyCapService.currentWeekStart(java.time.ZoneId.of("Asia/Tokyo"));
        assertThat(service.getCurrentCount(userId, weekStart)).isEqualTo(3);
    }

    @Test
    @DisplayName("実 Valkey: 同一広告主 2 件目で false（個人合計はまだ空きあり）")
    void 実Valkey_同一広告主上限() {
        Long userId = 5002L;
        Long advertiser = 100L;

        // 1 件目成功
        assertThat(service.tryConsume(userId, advertiser, UUID.randomUUID())).isTrue();
        // 2 件目（同一広告主上限超過）
        assertThat(service.tryConsume(userId, advertiser, UUID.randomUUID())).isFalse();

        // 別広告主なら成功
        assertThat(service.tryConsume(userId, 101L, UUID.randomUUID())).isTrue();

        // 個人合計は 2（広告主 100 の 1 件 + 広告主 101 の 1 件）
        LocalDate weekStart = AdFrequencyCapService.currentWeekStart(java.time.ZoneId.of("Asia/Tokyo"));
        assertThat(service.getCurrentCount(userId, weekStart)).isEqualTo(2);
    }

    @Test
    @DisplayName("実 Valkey: 同一ユーザー・同一広告主への並行 tryConsume は 1 回しか成功しない（原子性の実証）")
    void 実Valkey_並行tryConsumeは1回のみ成功() throws Exception {
        // AdCampaignDeliveryWorker のロック保持区間は候補一覧取得のみであり、配信ループ自体は
        // 排他されない。実際に同一ユーザーへの重複配信を防いでいるのは、この tryConsume の
        // Valkey INCR による原子性（同一週・同一広告主で 1 件までしか許可しない）である。
        // ここでは複数スレッドから真に同時に tryConsume を呼び、成功が高々 1 件であることを
        // 実 Valkey で検証する（Mockito では原子性そのものは検証できない）。
        Long userId = 5010L;
        Long advertiser = 900L;
        int concurrency = 20;

        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(concurrency);
        java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(concurrency);
        List<java.util.concurrent.Future<Boolean>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < concurrency; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await();
                    return service.tryConsume(userId, advertiser, UUID.randomUUID());
                }));
            }
            long successCount = 0;
            for (java.util.concurrent.Future<Boolean> f : futures) {
                if (f.get()) {
                    successCount++;
                }
            }

            // weeklyPerAdvertiser=1 のため、20 並行呼び出しのうち成功は 1 回のみ
            assertThat(successCount).isEqualTo(1L);

            LocalDate weekStart = AdFrequencyCapService.currentWeekStart(java.time.ZoneId.of("Asia/Tokyo"));
            String perAdvKey = AdFrequencyCapService.buildPerAdvertiserKey(advertiser, userId, weekStart);
            // ロールバックが正しく効いていれば、失敗した 19 回分は増減が相殺され最終値は 1
            String raw = redisTemplate.opsForValue().get(perAdvKey);
            assertThat(raw).isEqualTo("1");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("実 Valkey: TTL が次週月曜（ユーザー TZ）に近い値で設定される")
    void 実Valkey_TTL設定確認() {
        Long userId = 5003L;
        service.tryConsume(userId, 200L, UUID.randomUUID());

        LocalDate weekStart = AdFrequencyCapService.currentWeekStart(java.time.ZoneId.of("Asia/Tokyo"));
        String key = AdFrequencyCapService.buildTotalKey(userId, weekStart);
        Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);

        // TTL は 0 < ttl <= 7 日 + 余裕
        assertThat(ttl).isNotNull().isPositive().isLessThanOrEqualTo(7L * 24 * 3600);
    }

    @Test
    @DisplayName("flush バッチ: SCAN で 2 ユーザー分のカウンタを RDB 形式 entity に転記する")
    void flush_2ユーザーをRDBに転記() {
        // Given: 2 ユーザー × 異なる週で消費
        Long userA = 6001L;
        Long userB = 6002L;
        service.tryConsume(userA, 10L, UUID.randomUUID());
        service.tryConsume(userA, 11L, UUID.randomUUID());
        service.tryConsume(userB, 12L, UUID.randomUUID());

        // When: flush 実行
        int upserted = flushBatch.runFlush();

        // Then: 2 件分 upsert される
        assertThat(upserted).isEqualTo(2);

        List<UserAdDeliveryCounter> captured = new ArrayList<>();
        org.mockito.ArgumentCaptor<UserAdDeliveryCounter> cap =
                org.mockito.ArgumentCaptor.forClass(UserAdDeliveryCounter.class);
        org.mockito.Mockito.verify(counterRepository, org.mockito.Mockito.times(2)).save(cap.capture());
        captured.addAll(cap.getAllValues());

        // userA は count=2, userB は count=1
        UserAdDeliveryCounter aEntity = captured.stream()
                .filter(c -> c.getUserId().equals(userA)).findFirst().orElseThrow();
        UserAdDeliveryCounter bEntity = captured.stream()
                .filter(c -> c.getUserId().equals(userB)).findFirst().orElseThrow();
        assertThat(aEntity.getDeliveryCount()).isEqualTo(2);
        assertThat(bEntity.getDeliveryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("flush バッチ: 不正なキー形式は skip する")
    void flush_不正キーをskip() {
        // Given: 正しいキー 1 個 + 不正キー 1 個
        Long userId = 6101L;
        service.tryConsume(userId, 20L, UUID.randomUUID());
        // 不正な user_id 部分（数値でない）
        redisTemplate.opsForValue().set("mannschaft:ad:freq:not_a_number:2026-05-11", "99");

        // When
        int upserted = flushBatch.runFlush();

        // Then: 正規キー 1 件のみ upsert される
        assertThat(upserted).isEqualTo(1);
    }
}
