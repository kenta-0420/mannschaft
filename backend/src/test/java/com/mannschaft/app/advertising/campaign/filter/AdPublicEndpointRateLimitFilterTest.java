package com.mannschaft.app.advertising.campaign.filter;

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
 * {@link AdPublicEndpointRateLimitFilter} のユニットテスト（Valkey 化後）。
 * IP キーのみ・2 zone（unsubscribe 60/分・pixel 600/分）。
 */
class AdPublicEndpointRateLimitFilterTest {

    private static final long RESET_EPOCH = 1_750_000_020L;
    private static final long RETRY_AFTER = 20L;

    private AdPublicEndpointRateLimitFilter filter;
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
        filter = new AdPublicEndpointRateLimitFilter(provider);
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

    private MockHttpServletRequest req(String method, String path, String ip) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, path);
        r.setServletPath(path);
        r.setRemoteAddr(ip);
        return r;
    }

    @Nested
    @DisplayName("GET/POST /api/v1/ads/unsubscribe — 60 req/分・IP キー")
    class UnsubscribeLimit {

        @Test
        @DisplayName("GET unsubscribe: 60 回通過、61 回目で 429 / Retry-After / X-RateLimit-* / JSON ボディ")
        void getExceedsLimit() throws Exception {
            String ip = "10.0.0.1";
            for (int i = 0; i < 60; i++) {
                assertThat(invoke(req("GET", "/api/v1/ads/unsubscribe", ip)).getStatus())
                        .as("unsubscribe GET #%d", i + 1)
                        .isEqualTo(HttpStatus.OK.value());
            }
            MockHttpServletResponse over = invoke(req("GET", "/api/v1/ads/unsubscribe", ip));
            assertThat(over.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
            assertThat(over.getHeader("Retry-After")).isEqualTo(String.valueOf(RETRY_AFTER));
            assertThat(over.getHeader("X-RateLimit-Limit")).isEqualTo("60");
            assertThat(over.getContentAsString()).contains("Too many requests");

            verify(rateLimiter, atLeastOnce()).tryConsume(
                    eq("ad-public:unsubscribe"), eq("ip:" + ip), eq(60), eq(Duration.ofMinutes(1)));
        }

        @Test
        @DisplayName("POST unsubscribe も 60/分で制限される")
        void postExceedsLimit() throws Exception {
            String ip = "10.0.0.2";
            for (int i = 0; i < 60; i++) {
                assertThat(invoke(req("POST", "/api/v1/ads/unsubscribe", ip)).getStatus())
                        .isEqualTo(HttpStatus.OK.value());
            }
            assertThat(invoke(req("POST", "/api/v1/ads/unsubscribe", ip)).getStatus())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());

            verify(rateLimiter, atLeastOnce()).tryConsume(
                    eq("ad-public:unsubscribe"), eq("ip:" + ip), eq(60), eq(Duration.ofMinutes(1)));
        }

        @Test
        @DisplayName("§4.4: X-Forwarded-For がある場合は先頭値を IP キーに使う")
        void xForwardedFor() throws Exception {
            MockHttpServletRequest r = req("GET", "/api/v1/ads/unsubscribe", "10.0.0.99");
            r.addHeader("X-Forwarded-For", "203.0.113.1, 10.0.0.99");
            assertThat(invoke(r).getStatus()).isEqualTo(HttpStatus.OK.value());
            verify(rateLimiter).tryConsume(
                    eq("ad-public:unsubscribe"), eq("ip:203.0.113.1"), eq(60), any());
        }
    }

    @Nested
    @DisplayName("GET /api/v1/ads/pixels/open — 600 req/分・IP キー")
    class PixelOpenLimit {

        @Test
        @DisplayName("600 回通過、601 回目で 429")
        void exceedsLimit() throws Exception {
            String ip = "10.0.1.1";
            for (int i = 0; i < 600; i++) {
                assertThat(invoke(req("GET", "/api/v1/ads/pixels/open", ip)).getStatus())
                        .as("pixel GET #%d", i + 1)
                        .isEqualTo(HttpStatus.OK.value());
            }
            assertThat(invoke(req("GET", "/api/v1/ads/pixels/open", ip)).getStatus())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());

            verify(rateLimiter, atLeastOnce()).tryConsume(
                    eq("ad-public:pixel-open"), eq("ip:" + ip), eq(600), eq(Duration.ofMinutes(1)));
        }
    }

    @Nested
    @DisplayName("zone 分離 — unsubscribe と pixel は独立")
    class ZoneIsolation {

        @Test
        @DisplayName("unsubscribe を使い切っても pixel は独立して通る")
        void zonesAreIndependent() throws Exception {
            String ip = "10.0.2.1";

            for (int i = 0; i < 60; i++) {
                invoke(req("GET", "/api/v1/ads/unsubscribe", ip));
            }
            assertThat(invoke(req("GET", "/api/v1/ads/unsubscribe", ip)).getStatus())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());

            // pixel は別 zone のためまだ通る
            assertThat(invoke(req("GET", "/api/v1/ads/pixels/open", ip)).getStatus())
                    .isEqualTo(HttpStatus.OK.value());
        }
    }

    @Nested
    @DisplayName("対象外エンドポイントはスキップされる")
    class SkippedEndpoints {

        @Test
        @DisplayName("DELETE unsubscribe はフィルタ対象外")
        void deleteIsNotFiltered() {
            assertThat(filter.shouldNotFilter(req("DELETE", "/api/v1/ads/unsubscribe", "10.0.0.1"))).isTrue();
        }

        @Test
        @DisplayName("POST pixel はフィルタ対象外（GET のみ）")
        void postPixelIsNotFiltered() {
            assertThat(filter.shouldNotFilter(req("POST", "/api/v1/ads/pixels/open", "10.0.0.1"))).isTrue();
        }
    }

    @Nested
    @DisplayName("ValkeyRateLimiter Bean 不在（最小テストコンテキスト互換）")
    class LimiterBeanAbsent {

        @Test
        @DisplayName("ValkeyRateLimiter が解決できない場合は素通しする")
        @SuppressWarnings("unchecked")
        void passesThroughWhenLimiterUnavailable() throws Exception {
            ObjectProvider<ValkeyRateLimiter> emptyProvider = mock(ObjectProvider.class);
            when(emptyProvider.getIfAvailable()).thenReturn(null);
            AdPublicEndpointRateLimitFilter beanlessFilter =
                    new AdPublicEndpointRateLimitFilter(emptyProvider);

            MockHttpServletResponse response = new MockHttpServletResponse();
            beanlessFilter.doFilter(req("GET", "/api/v1/ads/unsubscribe", "10.0.7.1"),
                    response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        }
    }
}
