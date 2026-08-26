package com.mannschaft.app.dashboard;

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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.util.List;
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
import static org.mockito.Mockito.verify;

/**
 * {@link DashboardScopeTabRateLimitFilter} のユニットテスト（Valkey 化後）。
 */
class DashboardScopeTabRateLimitFilterTest {

    private static final long RESET_EPOCH = 1_750_000_020L;
    private static final long RETRY_AFTER = 20L;

    private DashboardScopeTabRateLimitFilter filter;
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
        filter = new DashboardScopeTabRateLimitFilter(provider);
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

    private MockHttpServletRequest putScopeTabs(String ip) {
        MockHttpServletRequest req = new MockHttpServletRequest("PUT", "/api/v1/dashboard/scope-tabs/order");
        req.setServletPath("/api/v1/dashboard/scope-tabs/order");
        req.setRemoteAddr(ip);
        return req;
    }

    private void authenticateAs(String userId) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                userId, "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Nested
    @DisplayName("PUT /api/v1/dashboard/scope-tabs/order — 30 req/分")
    class ScopeTabsOrderLimit {

        @Test
        @DisplayName("同一 IP から 30 回までは通過、31 回目で 429 / Retry-After / X-RateLimit-* / JSON ボディ")
        void exceedsLimitReturns429() throws Exception {
            String ip = "10.0.0.1";

            for (int i = 0; i < 30; i++) {
                assertThat(invoke(putScopeTabs(ip)).getStatus())
                        .as("scope-tabs PUT #%d should pass", i + 1)
                        .isEqualTo(HttpStatus.OK.value());
            }

            MockHttpServletResponse overLimit = invoke(putScopeTabs(ip));
            assertThat(overLimit.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
            assertThat(overLimit.getHeader("Retry-After")).isEqualTo(String.valueOf(RETRY_AFTER));
            assertThat(overLimit.getHeader("X-RateLimit-Limit")).isEqualTo("30");
            assertThat(overLimit.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
            assertThat(overLimit.getHeader("X-RateLimit-Reset")).isEqualTo(String.valueOf(RESET_EPOCH));
            assertThat(overLimit.getContentAsString()).contains("Too many requests");

            verify(rateLimiter, atLeastOnce()).tryConsume(
                    eq("dashboard:scope-tabs-order"), eq("ip:" + ip), eq(30), eq(Duration.ofMinutes(1)));
        }

        @Test
        @DisplayName("§4.3: 通過時にも X-RateLimit-* ヘッダーが付与される")
        void standardHeadersOnSuccess() throws Exception {
            MockHttpServletResponse response = invoke(putScopeTabs("10.0.0.8"));
            assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
            assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("30");
            assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("29");
            assertThat(response.getHeader("Retry-After")).isNull();
        }

        @Test
        @DisplayName("認証済みユーザーは u:{userId} キーでカウントされる")
        void authenticatedUserKeyedByUserId() throws Exception {
            authenticateAs("user-alice");

            for (int i = 0; i < 30; i++) {
                assertThat(invoke(putScopeTabs("10.0.0.9")).getStatus()).isEqualTo(HttpStatus.OK.value());
            }
            assertThat(invoke(putScopeTabs("10.0.0.9")).getStatus())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
            verify(rateLimiter, atLeastOnce()).tryConsume(
                    eq("dashboard:scope-tabs-order"), eq("u:user-alice"), eq(30), any());
        }
    }

    @Nested
    @DisplayName("対象外エンドポイントはスキップされる")
    class SkippedEndpoints {

        @Test
        @DisplayName("GET /api/v1/dashboard/scope-tabs/order はフィルタ対象外")
        void getIsNotFiltered() {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/dashboard/scope-tabs/order");
            req.setServletPath("/api/v1/dashboard/scope-tabs/order");
            assertThat(filter.shouldNotFilter(req)).isTrue();
        }

        @Test
        @DisplayName("PUT /api/v1/other はフィルタ対象外")
        void otherPathIsNotFiltered() {
            MockHttpServletRequest req = new MockHttpServletRequest("PUT", "/api/v1/other");
            req.setServletPath("/api/v1/other");
            assertThat(filter.shouldNotFilter(req)).isTrue();
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
            DashboardScopeTabRateLimitFilter beanlessFilter =
                    new DashboardScopeTabRateLimitFilter(emptyProvider);

            MockHttpServletResponse response = new MockHttpServletResponse();
            beanlessFilter.doFilter(putScopeTabs("10.0.7.1"), response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        }
    }
}
