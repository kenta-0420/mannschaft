package com.mannschaft.app.errorreport;

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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ErrorReportRateLimitFilter} のユニットテスト（Valkey 化後）。
 *
 * <p>{@link ValkeyRateLimiter} はモックし、フィルタの責務である
 * 「エンドポイント判定 / IP キー解決 / 429 応答・§4.3 ヘッダー」を検証する。</p>
 */
class ErrorReportRateLimitFilterTest {

    private static final long RESET_EPOCH = 1_750_000_020L;
    private static final long RETRY_AFTER = 20L;

    private ErrorReportRateLimitFilter filter;
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
        filter = new ErrorReportRateLimitFilter(provider);
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

    private MockHttpServletRequest postErrorReport(String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/error-reports");
        request.setServletPath("/api/v1/error-reports");
        request.setRemoteAddr(ip);
        return request;
    }

    @Nested
    @DisplayName("POST /api/v1/error-reports — 10 req/分・IP キー")
    class ErrorReportLimit {

        @Test
        @DisplayName("同一 IP から 10 回までは通過、11 回目で 429 / Retry-After / X-RateLimit-* / JSON ボディ")
        void exceedsLimitReturns429() throws Exception {
            String ip = "10.0.0.1";

            for (int i = 0; i < 10; i++) {
                MockHttpServletResponse response = invoke(postErrorReport(ip));
                assertThat(response.getStatus())
                        .as("error-reports POST #%d should pass", i + 1)
                        .isEqualTo(HttpStatus.OK.value());
            }

            MockHttpServletResponse overLimit = invoke(postErrorReport(ip));
            assertThat(overLimit.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
            assertThat(overLimit.getHeader("Retry-After")).isEqualTo(String.valueOf(RETRY_AFTER));
            assertThat(overLimit.getHeader("X-RateLimit-Limit")).isEqualTo("10");
            assertThat(overLimit.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
            assertThat(overLimit.getHeader("X-RateLimit-Reset")).isEqualTo(String.valueOf(RESET_EPOCH));
            assertThat(overLimit.getContentAsString()).contains("Too many requests");

            // zone / limit / window が宣言どおり
            verify(rateLimiter, atLeastOnce()).tryConsume(
                    eq("errorreport:create"), eq("ip:" + ip), eq(10), eq(Duration.ofMinutes(1)));
        }

        @Test
        @DisplayName("§4.3: 通過時にも X-RateLimit-* ヘッダーが付与される")
        void standardHeadersOnSuccess() throws Exception {
            MockHttpServletResponse response = invoke(postErrorReport("10.0.0.8"));
            assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
            assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("10");
            assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("9");
            assertThat(response.getHeader("Retry-After")).isNull();
        }

        @Test
        @DisplayName("異なる IP はバケットが独立しており相互に影響しない")
        void bucketsAreIsolatedByIp() throws Exception {
            String ipA = "10.0.0.2";
            String ipB = "10.0.0.3";

            for (int i = 0; i < 10; i++) {
                assertThat(invoke(postErrorReport(ipA)).getStatus()).isEqualTo(HttpStatus.OK.value());
            }
            assertThat(invoke(postErrorReport(ipA)).getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());

            // ipB は独立
            assertThat(invoke(postErrorReport(ipB)).getStatus()).isEqualTo(HttpStatus.OK.value());
        }

        @Test
        @DisplayName("§4.4: X-Forwarded-For がある場合は先頭値を IP キーに使う")
        void xForwardedForTakesPrecedence() throws Exception {
            MockHttpServletRequest req = postErrorReport("10.0.0.99");
            req.addHeader("X-Forwarded-For", "203.0.113.5, 10.0.0.99");

            assertThat(invoke(req).getStatus()).isEqualTo(HttpStatus.OK.value());
            verify(rateLimiter).tryConsume(
                    eq("errorreport:create"), eq("ip:203.0.113.5"), eq(10), any());
        }
    }

    @Nested
    @DisplayName("対象外エンドポイントはスキップされる")
    class SkippedEndpoints {

        @Test
        @DisplayName("GET /api/v1/error-reports はフィルタ対象外（shouldNotFilter=true）")
        void getIsNotFiltered() {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/error-reports");
            request.setServletPath("/api/v1/error-reports");
            assertThat(filter.shouldNotFilter(request)).isTrue();
        }

        @Test
        @DisplayName("POST /api/v1/other はフィルタ対象外")
        void otherPathIsNotFiltered() {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/other");
            request.setServletPath("/api/v1/other");
            assertThat(filter.shouldNotFilter(request)).isTrue();
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
            ErrorReportRateLimitFilter beanlessFilter = new ErrorReportRateLimitFilter(emptyProvider);

            MockHttpServletRequest request = postErrorReport("10.0.7.1");
            MockHttpServletResponse response = new MockHttpServletResponse();
            beanlessFilter.doFilter(request, response, new MockFilterChain());

            assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        }
    }
}
