package com.mannschaft.app.common.ratelimit;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ValkeyRateLimiter} のユニットテスト（純 Mockito）。
 *
 * <p>Lua スクリプト実行はモックで代替し、以下を検証する:</p>
 * <ul>
 *   <li>Valkey キー（{@code mannschaft:rate:{zone}:{key}:{windowStart}}）と TTL（ウィンドウ長+5秒）の計算</li>
 *   <li>カウント値に応じた allowed / remaining / reset / retryAfter の算出</li>
 *   <li>fail-open: {@code DataAccessException} / Bean 不在 / null 結果でも allowed=true + メトリクス記録</li>
 * </ul>
 *
 * <p>実 Redis に対するカウント・TTL・ウィンドウ境界の挙動は
 * {@link ValkeyRateLimiterIntegrationTest}（Testcontainers）の責務。</p>
 */
@DisplayName("ValkeyRateLimiter ユニットテスト")
class ValkeyRateLimiterTest {

    /** 固定時刻: 2025-06-15T... 相当の Unix 秒。60 秒ウィンドウの切り捨て検証に使う。 */
    private static final long NOW_EPOCH_SECONDS = 1_750_000_000L;
    /** NOW を 60 秒で切り捨てた windowStart。 */
    private static final long WINDOW_START_60S = 1_749_999_960L;
    /** 60 秒ウィンドウのリセット時刻。 */
    private static final long RESET_60S = WINDOW_START_60S + 60L;

    private StringRedisTemplate redisTemplate;
    private SimpleMeterRegistry meterRegistry;
    private ValkeyRateLimiter limiter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);

        ObjectProvider<StringRedisTemplate> redisProvider = mock(ObjectProvider.class);
        when(redisProvider.getIfAvailable()).thenReturn(redisTemplate);

        meterRegistry = new SimpleMeterRegistry();
        ObjectProvider<MeterRegistry> meterProvider = mock(ObjectProvider.class);
        doAnswer(invocation -> {
            Consumer<MeterRegistry> consumer = invocation.getArgument(0);
            consumer.accept(meterRegistry);
            return null;
        }).when(meterProvider).ifAvailable(any());

        limiter = new ValkeyRateLimiter(redisProvider, meterProvider);
        limiter.setClock(Clock.fixed(Instant.ofEpochSecond(NOW_EPOCH_SECONDS), ZoneOffset.UTC));
    }

    @SuppressWarnings("unchecked")
    private void stubCount(long count) {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(count);
    }

    @Nested
    @DisplayName("キー・TTL 計算")
    class KeyAndTtl {

        @Test
        @DisplayName("Valkey キーは mannschaft:rate:{zone}:{key}:{windowStart}、TTL はウィンドウ長+5秒")
        @SuppressWarnings("unchecked")
        void keyAndTtlComputation() {
            stubCount(1L);

            limiter.tryConsume("test-zone", "u:42", 60, Duration.ofSeconds(60));

            ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
            ArgumentCaptor<String> ttlCaptor = ArgumentCaptor.forClass(String.class);
            org.mockito.Mockito.verify(redisTemplate).execute(
                    any(RedisScript.class), keysCaptor.capture(), ttlCaptor.capture());

            assertThat(keysCaptor.getValue())
                    .containsExactly("mannschaft:rate:test-zone:u:42:" + WINDOW_START_60S);
            assertThat(ttlCaptor.getValue()).isEqualTo("65");
        }

        @Test
        @DisplayName("ウィンドウ長 300 秒なら windowStart は 300 秒単位で切り捨て、TTL は 305 秒")
        @SuppressWarnings("unchecked")
        void longerWindow() {
            stubCount(1L);
            long windowStart300 = (NOW_EPOCH_SECONDS / 300L) * 300L;

            RateLimitResult result = limiter.tryConsume("zone300", "ip:10.0.0.1", 10, Duration.ofSeconds(300));

            ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
            ArgumentCaptor<String> ttlCaptor = ArgumentCaptor.forClass(String.class);
            org.mockito.Mockito.verify(redisTemplate).execute(
                    any(RedisScript.class), keysCaptor.capture(), ttlCaptor.capture());

            assertThat(keysCaptor.getValue())
                    .containsExactly("mannschaft:rate:zone300:ip:10.0.0.1:" + windowStart300);
            assertThat(ttlCaptor.getValue()).isEqualTo("305");
            assertThat(result.resetEpochSeconds()).isEqualTo(windowStart300 + 300L);
        }
    }

    @Nested
    @DisplayName("カウント判定（allowed / remaining / reset / retryAfter）")
    class CountJudgement {

        @Test
        @DisplayName("count=1（初回）: allowed=true, remaining=limit-1")
        void firstRequestAllowed() {
            stubCount(1L);

            RateLimitResult result = limiter.tryConsume("z", "u:1", 60, Duration.ofSeconds(60));

            assertThat(result.allowed()).isTrue();
            assertThat(result.limit()).isEqualTo(60);
            assertThat(result.remaining()).isEqualTo(59);
            assertThat(result.resetEpochSeconds()).isEqualTo(RESET_60S);
            // NOW=…000, reset=…020 → retryAfter は 20 秒
            assertThat(result.retryAfterSeconds()).isEqualTo(RESET_60S - NOW_EPOCH_SECONDS);
        }

        @Test
        @DisplayName("count=limit（上限ちょうど）: allowed=true, remaining=0")
        void atLimitStillAllowed() {
            stubCount(60L);

            RateLimitResult result = limiter.tryConsume("z", "u:1", 60, Duration.ofSeconds(60));

            assertThat(result.allowed()).isTrue();
            assertThat(result.remaining()).isZero();
        }

        @Test
        @DisplayName("count=limit+1（超過）: allowed=false, remaining=0")
        void overLimitDenied() {
            stubCount(61L);

            RateLimitResult result = limiter.tryConsume("z", "u:1", 60, Duration.ofSeconds(60));

            assertThat(result.allowed()).isFalse();
            assertThat(result.remaining()).isZero();
            assertThat(result.resetEpochSeconds()).isEqualTo(RESET_60S);
            assertThat(result.retryAfterSeconds()).isPositive();
        }
    }

    @Nested
    @DisplayName("fail-open（可用性優先・必ずメトリクスで可視化）")
    class FailOpen {

        @Test
        @DisplayName("DataAccessException 発生時は allowed=true（fail-open）+ メトリクス記録")
        @SuppressWarnings("unchecked")
        void dataAccessExceptionFailsOpen() {
            when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
                    .thenThrow(new DataAccessResourceFailureException("Valkey down"));

            RateLimitResult result = limiter.tryConsume("z", "u:1", 60, Duration.ofSeconds(60));

            assertThat(result.allowed()).isTrue();
            assertThat(result.remaining()).isEqualTo(60);
            double count = meterRegistry.counter(
                    ValkeyRateLimiter.FAIL_OPEN_METRIC, "zone", "z", "reason", "data_access").count();
            assertThat(count).isEqualTo(1.0);
        }

        @Test
        @DisplayName("Redis Bean 不在（テストスライス等）でも allowed=true（fail-open）")
        @SuppressWarnings("unchecked")
        void missingRedisBeanFailsOpen() {
            ObjectProvider<StringRedisTemplate> emptyProvider = mock(ObjectProvider.class);
            when(emptyProvider.getIfAvailable()).thenReturn(null);
            ObjectProvider<MeterRegistry> meterProvider = mock(ObjectProvider.class);
            doAnswer(invocation -> {
                Consumer<MeterRegistry> consumer = invocation.getArgument(0);
                consumer.accept(meterRegistry);
                return null;
            }).when(meterProvider).ifAvailable(any());
            ValkeyRateLimiter beanlessLimiter = new ValkeyRateLimiter(emptyProvider, meterProvider);

            RateLimitResult result = beanlessLimiter.tryConsume("z", "u:1", 30, Duration.ofSeconds(60));

            assertThat(result.allowed()).isTrue();
            assertThat(result.remaining()).isEqualTo(30);
            double count = meterRegistry.counter(
                    ValkeyRateLimiter.FAIL_OPEN_METRIC, "zone", "z", "reason", "no_redis_bean").count();
            assertThat(count).isEqualTo(1.0);
        }

        @Test
        @DisplayName("Lua 実行結果が null の場合も allowed=true（fail-open）")
        @SuppressWarnings("unchecked")
        void nullResultFailsOpen() {
            when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(null);

            RateLimitResult result = limiter.tryConsume("z", "u:1", 60, Duration.ofSeconds(60));

            assertThat(result.allowed()).isTrue();
            double count = meterRegistry.counter(
                    ValkeyRateLimiter.FAIL_OPEN_METRIC, "zone", "z", "reason", "null_result").count();
            assertThat(count).isEqualTo(1.0);
        }
    }
}
