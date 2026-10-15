package com.mannschaft.app.sync;

import com.mannschaft.app.common.ratelimit.RateLimitResult;
import com.mannschaft.app.common.ratelimit.ValkeyRateLimiter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link SyncRateLimitFilter} のユニットテスト（Valkey 化後）。
 *
 * <p>{@link ValkeyRateLimiter} はモックし、フィルタの責務である
 * 「エンドポイント判定 / (zone, limit, window) 宣言 / キー解決 / 429 応答・§4.3 ヘッダー」を検証する。</p>
 */
class SyncRateLimitFilterTest {

    private static final long RESET_EPOCH = 1_750_000_020L;
    private static final long RETRY_AFTER = 20L;

    private SyncRateLimitFilter filter;
    private ValkeyRateLimiter rateLimiter;
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        rateLimiter = mock(ValkeyRateLimiter.class);
        when(rateLimiter.tryConsume(anyString(), anyString(), anyInt(), any(Duration.class)))
                .thenAnswer(inv -> {
                    String zone = inv.getArgument(0);
                    String key = inv.getArgument(1);
                    int limit = inv.getArgument(2);
                    long count = counters
                            .computeIfAbsent(zone + "|" + key, k -> new AtomicLong())
                            .incrementAndGet();
                    return new RateLimitResult(
                            count <= limit, limit, Math.max(0, limit - count), RESET_EPOCH, RETRY_AFTER);
                });

        ObjectProvider<ValkeyRateLimiter> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(rateLimiter);
        filter = new SyncRateLimitFilter(provider);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        counters.clear();
    }

    private MockHttpServletResponse invoke(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return response;
    }

    private MockHttpServletRequest syncPost(String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/sync");
        request.setServletPath("/api/v1/sync");
        request.setRemoteAddr(ip);
        return request;
    }

    private MockHttpServletRequest conflictGet(String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/sync/conflicts");
        request.setServletPath("/api/v1/sync/conflicts");
        request.setRemoteAddr(ip);
        return request;
    }

    @Nested
    @DisplayName("POST /api/v1/sync — 1分10回制限")
    class SyncPostLimit {

        @Test
        @DisplayName("同一 IP から 10 回までは通過、11 回目で 429 / 標準ヘッダー付与")
        void syncPostExceedsLimit() throws Exception {
            String ip = "10.0.0.1";

            for (int i = 0; i < 10; i++) {
                MockHttpServletResponse response = invoke(syncPost(ip));
                assertThat(response.getStatus())
                        .as("sync POST #%d should pass", i + 1)
                        .isEqualTo(HttpStatus.OK.value());
            }

            MockHttpServletResponse overLimit = invoke(syncPost(ip));
            assertThat(overLimit.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
            assertThat(overLimit.getHeader("Retry-After")).isEqualTo(String.valueOf(RETRY_AFTER));
            assertThat(overLimit.getHeader("X-RateLimit-Limit")).isEqualTo("10");
            assertThat(overLimit.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
            assertThat(overLimit.getHeader("X-RateLimit-Reset")).isEqualTo(String.valueOf(RESET_EPOCH));

            verify(rateLimiter, atLeastOnce()).tryConsume(
                    eq("sync:POST"), eq("ip:" + ip), eq(10), eq(Duration.ofMinutes(1)));
        }

        @Test
        @DisplayName("§4.3: 通過時にも X-RateLimit-* ヘッダーが付与される")
        void standardHeadersOnSuccess() throws Exception {
            MockHttpServletResponse response = invoke(syncPost("10.0.0.8"));
            assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
            assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("10");
            assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("9");
            assertThat(response.getHeader("X-RateLimit-Reset")).isEqualTo(String.valueOf(RESET_EPOCH));
            assertThat(response.getHeader("Retry-After")).isNull();
        }

        @Test
        @DisplayName("異なる IP はバケットが独立しており相互に影響しない")
        void syncPostIsolatedByIp() throws Exception {
            String ipA = "10.0.0.2";
            String ipB = "10.0.0.3";

            for (int i = 0; i < 10; i++) {
                assertThat(invoke(syncPost(ipA)).getStatus()).isEqualTo(HttpStatus.OK.value());
            }
            assertThat(invoke(syncPost(ipA)).getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());

            assertThat(invoke(syncPost(ipB)).getStatus()).isEqualTo(HttpStatus.OK.value());
        }
    }

    @Nested
    @DisplayName("conflicts 系 — 1分60回制限")
    class ConflictsLimit {

        @Test
        @DisplayName("同一 IP から 60 回までは通過、61 回目で 429")
        void conflictGetExceedsLimit() throws Exception {
            String ip = "10.0.1.1";

            for (int i = 0; i < 60; i++) {
                assertThat(invoke(conflictGet(ip)).getStatus())
                        .as("conflicts GET #%d should pass", i + 1)
                        .isEqualTo(HttpStatus.OK.value());
            }

            MockHttpServletResponse overLimit = invoke(conflictGet(ip));
            assertThat(overLimit.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
            verify(rateLimiter, atLeastOnce()).tryConsume(
                    eq("sync:CONFLICTS"), eq("ip:" + ip), eq(60), eq(Duration.ofMinutes(1)));
        }

        @Test
        @DisplayName("sync POST と conflicts のゾーンは独立している")
        void syncAndConflictsAreSeparate() throws Exception {
            String ip = "10.0.1.2";

            for (int i = 0; i < 10; i++) {
                assertThat(invoke(syncPost(ip)).getStatus()).isEqualTo(HttpStatus.OK.value());
            }
            assertThat(invoke(syncPost(ip)).getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());

            // conflicts は zone が異なるため独立して通過する
            assertThat(invoke(conflictGet(ip)).getStatus()).isEqualTo(HttpStatus.OK.value());
        }
    }

    @Nested
    @DisplayName("ValkeyRateLimiter Bean 不在（最小テストコンテキスト互換）")
    class LimiterBeanAbsent {

        @Test
        @DisplayName("ValkeyRateLimiter が解決できない場合は素通しする（@WebMvcTest スライス互換）")
        @SuppressWarnings("unchecked")
        void passesThroughWhenLimiterUnavailable() throws Exception {
            ObjectProvider<ValkeyRateLimiter> emptyProvider = mock(ObjectProvider.class);
            when(emptyProvider.getIfAvailable()).thenReturn(null);
            SyncRateLimitFilter beanlessFilter = new SyncRateLimitFilter(emptyProvider);

            MockHttpServletResponse response = new MockHttpServletResponse();
            beanlessFilter.doFilter(syncPost("10.0.7.1"), response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        }
    }

    // Mockito verify ヘルパー（static import なし回避用）
    private static <T> T verify(T mock, org.mockito.verification.VerificationMode mode) {
        return org.mockito.Mockito.verify(mock, mode);
    }
}
