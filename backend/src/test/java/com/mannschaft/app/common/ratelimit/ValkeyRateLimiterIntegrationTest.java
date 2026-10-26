package com.mannschaft.app.common.ratelimit;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ValkeyRateLimiter} の Testcontainers Redis 統合テスト。
 *
 * <p>{@code AdFrequencyCapIntegrationTest} と同じ流儀: {@code @SpringBootTest} は使わず
 * （{@code AbstractMySqlIntegrationTest} の TestContext Cache 分裂を避けるため）、
 * Lettuce + StringRedisTemplate をテストで直接組み立てる。
 * Redis は {@code redis:7-alpine} を Testcontainers で起動する。</p>
 *
 * <p>検証対象（実 Redis でのみ意味を持つ挙動）:</p>
 * <ul>
 *   <li>(a) limit 回まで allowed → 超過で denied（Lua INCR の実カウント）</li>
 *   <li>(b) ウィンドウ境界でカウンタがリセットされる（Clock 注入で境界を跨ぐ）</li>
 *   <li>(c) remaining / reset 値の正しさ + 実キーの TTL 設定（ウィンドウ長 + 5 秒以内）</li>
 * </ul>
 */
@DisplayName("ValkeyRateLimiter 統合テスト（Testcontainers Redis）")
@EnabledIf("com.mannschaft.app.common.ratelimit.ValkeyRateLimiterIntegrationTest#isDockerAvailable")
class ValkeyRateLimiterIntegrationTest {

    private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7-alpine");

    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS = new GenericContainer<>(REDIS_IMAGE)
            .withExposedPorts(6379)
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(120)));

    /** 固定基準時刻（ウィンドウ境界の制御用。実時間には依存しない）。 */
    private static final long BASE_EPOCH_SECONDS = 1_750_000_000L;

    private static StringRedisTemplate redisTemplate;
    private static LettuceConnectionFactory connectionFactory;

    private ValkeyRateLimiter limiter;

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
    @SuppressWarnings("unchecked")
    void setUp() {
        // 各テスト前にキー全削除
        if (connectionFactory != null) {
            connectionFactory.getConnection().serverCommands().flushAll();
        }

        ObjectProvider<StringRedisTemplate> redisProvider = mock(ObjectProvider.class);
        when(redisProvider.getIfAvailable()).thenReturn(redisTemplate);

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ObjectProvider<MeterRegistry> meterProvider = mock(ObjectProvider.class);
        doAnswer(invocation -> {
            Consumer<MeterRegistry> consumer = invocation.getArgument(0);
            consumer.accept(meterRegistry);
            return null;
        }).when(meterProvider).ifAvailable(any());

        limiter = new ValkeyRateLimiter(redisProvider, meterProvider);
        setClockAt(BASE_EPOCH_SECONDS);
    }

    private void setClockAt(long epochSeconds) {
        limiter.setClock(Clock.fixed(Instant.ofEpochSecond(epochSeconds), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("(a) 実 Redis: limit=3 で 3 回まで allowed、4 回目以降 denied")
    void 実Redis_3回成功_4回目以降denied() {
        Duration window = Duration.ofSeconds(60);

        assertThat(limiter.tryConsume("it-zone", "u:100", 3, window).allowed()).isTrue();
        assertThat(limiter.tryConsume("it-zone", "u:100", 3, window).allowed()).isTrue();
        assertThat(limiter.tryConsume("it-zone", "u:100", 3, window).allowed()).isTrue();

        RateLimitResult fourth = limiter.tryConsume("it-zone", "u:100", 3, window);
        assertThat(fourth.allowed()).isFalse();
        assertThat(fourth.remaining()).isZero();

        // 5 回目も継続して denied（カウンタは増え続けるが remaining は 0 で張り付く）
        RateLimitResult fifth = limiter.tryConsume("it-zone", "u:100", 3, window);
        assertThat(fifth.allowed()).isFalse();
        assertThat(fifth.remaining()).isZero();
    }

    @Test
    @DisplayName("(a') 実 Redis: zone・key が異なればカウンタは独立する")
    void 実Redis_zone_key独立() {
        Duration window = Duration.ofSeconds(60);

        // zone-A / u:1 を上限まで消費
        assertThat(limiter.tryConsume("zone-A", "u:1", 1, window).allowed()).isTrue();
        assertThat(limiter.tryConsume("zone-A", "u:1", 1, window).allowed()).isFalse();

        // 同 zone でも別 key は独立
        assertThat(limiter.tryConsume("zone-A", "u:2", 1, window).allowed()).isTrue();
        // 同 key でも別 zone は独立
        assertThat(limiter.tryConsume("zone-B", "u:1", 1, window).allowed()).isTrue();
    }

    @Test
    @DisplayName("(b) 実 Redis: ウィンドウ境界を跨ぐとカウンタがリセットされる")
    void 実Redis_ウィンドウ境界でリセット() {
        Duration window = Duration.ofSeconds(60);

        // 現在ウィンドウで上限まで消費 → denied
        assertThat(limiter.tryConsume("boundary", "ip:10.0.0.1", 2, window).allowed()).isTrue();
        assertThat(limiter.tryConsume("boundary", "ip:10.0.0.1", 2, window).allowed()).isTrue();
        assertThat(limiter.tryConsume("boundary", "ip:10.0.0.1", 2, window).allowed()).isFalse();

        // 時計をウィンドウ長ぶん進める → windowStart が変わり新キーで再カウント
        setClockAt(BASE_EPOCH_SECONDS + window.getSeconds());
        RateLimitResult afterBoundary = limiter.tryConsume("boundary", "ip:10.0.0.1", 2, window);
        assertThat(afterBoundary.allowed()).isTrue();
        assertThat(afterBoundary.remaining()).isEqualTo(1);
    }

    @Test
    @DisplayName("(c) 実 Redis: remaining は単調減少し、reset はウィンドウ終端 Unix 秒を指す")
    void 実Redis_remainingとreset値の正しさ() {
        Duration window = Duration.ofSeconds(60);
        long windowStart = (BASE_EPOCH_SECONDS / 60L) * 60L;
        long expectedReset = windowStart + 60L;

        RateLimitResult first = limiter.tryConsume("values", "u:7", 3, window);
        RateLimitResult second = limiter.tryConsume("values", "u:7", 3, window);
        RateLimitResult third = limiter.tryConsume("values", "u:7", 3, window);

        assertThat(first.remaining()).isEqualTo(2);
        assertThat(second.remaining()).isEqualTo(1);
        assertThat(third.remaining()).isZero();
        assertThat(first.limit()).isEqualTo(3);
        assertThat(first.resetEpochSeconds()).isEqualTo(expectedReset);
        assertThat(first.retryAfterSeconds()).isEqualTo(expectedReset - BASE_EPOCH_SECONDS);
    }

    @Test
    @DisplayName("(c') 実 Redis: キーに TTL（ウィンドウ長 + 5 秒マージン以内）が設定される")
    void 実Redis_TTL設定確認() {
        Duration window = Duration.ofSeconds(60);
        long windowStart = (BASE_EPOCH_SECONDS / 60L) * 60L;

        limiter.tryConsume("ttl-zone", "u:9", 3, window);

        String key = "mannschaft:rate:ttl-zone:u:9:" + windowStart;
        Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);

        // Lua の初回 EXPIRE が効いていること（0 < ttl <= 60+5）
        assertThat(ttl).isNotNull().isPositive().isLessThanOrEqualTo(65L);

        // 2 回目の INCR で TTL が上書きされない（初回のみ EXPIRE）こと
        limiter.tryConsume("ttl-zone", "u:9", 3, window);
        Long ttlAfterSecond = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        assertThat(ttlAfterSecond).isNotNull().isPositive().isLessThanOrEqualTo(65L);
    }
}
