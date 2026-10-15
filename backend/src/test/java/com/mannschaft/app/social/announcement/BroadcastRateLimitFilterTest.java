package com.mannschaft.app.social.announcement;

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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link BroadcastRateLimitFilter} のユニットテスト（Valkey 化後）。
 * ウィンドウは 5 分（旧実装 Refill.greedy(5, ofMinutes(5)) と同等）。
 */
class BroadcastRateLimitFilterTest {

    private static final long RESET_EPOCH = 1_750_000_020L;
    private static final long RETRY_AFTER = 20L;

    private BroadcastRateLimitFilter filter;
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
        filter = new BroadcastRateLimitFilter(provider);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        counters.clear();
    }

    private void authenticateAs(String userId) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                userId, "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private MockHttpServletResponse invoke(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return response;
    }

    private MockHttpServletRequest postBroadcast(String scopeType, String scopeId, String ip) {
        String path = "/api/v1/" + scopeType + "/" + scopeId + "/broadcast";
        MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
        req.setServletPath(path);
        req.setRemoteAddr(ip);
        return req;
    }

    @Nested
    @DisplayName("broadcast — 5 req/5分・認証済みユーザーキー")
    class BroadcastLimit {

        @Test
        @DisplayName("認証済みユーザーが 5 回まで通過、6 回目で 429 / Retry-After / X-RateLimit-* / JSON ボディ")
        void exceedsLimitReturns429() throws Exception {
            authenticateAs("user-alice");
            String ip = "10.0.0.1";

            for (int i = 0; i < 5; i++) {
                assertThat(invoke(postBroadcast("teams", "1", ip)).getStatus())
                        .as("broadcast POST #%d should pass", i + 1)
                        .isEqualTo(HttpStatus.OK.value());
            }

            MockHttpServletResponse overLimit = invoke(postBroadcast("teams", "1", ip));
            assertThat(overLimit.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
            assertThat(overLimit.getHeader("Retry-After")).isEqualTo(String.valueOf(RETRY_AFTER));
            assertThat(overLimit.getHeader("X-RateLimit-Limit")).isEqualTo("5");
            assertThat(overLimit.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
            assertThat(overLimit.getHeader("X-RateLimit-Reset")).isEqualTo(String.valueOf(RESET_EPOCH));
            assertThat(overLimit.getContentAsString()).contains("Too many requests");

            // zone / limit / window（5分）が宣言どおり
            verify(rateLimiter, atLeastOnce()).tryConsume(
                    eq("broadcast:send"), eq("u:user-alice"), eq(5), eq(Duration.ofMinutes(5)));
        }

        @Test
        @DisplayName("organizations スコープでも同じ zone で制限される")
        void organizationScopeUsed() throws Exception {
            authenticateAs("user-bob");

            for (int i = 0; i < 5; i++) {
                assertThat(invoke(postBroadcast("organizations", "99", "10.0.0.2")).getStatus())
                        .isEqualTo(HttpStatus.OK.value());
            }
            assertThat(invoke(postBroadcast("organizations", "99", "10.0.0.2")).getStatus())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());

            verify(rateLimiter, atLeastOnce()).tryConsume(
                    eq("broadcast:send"), eq("u:user-bob"), eq(5), eq(Duration.ofMinutes(5)));
        }
    }

    @Nested
    @DisplayName("対象外エンドポイントはスキップされる")
    class SkippedEndpoints {

        @Test
        @DisplayName("GET broadcast はフィルタ対象外")
        void getIsNotFiltered() {
            authenticateAs("user-alice");
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/teams/1/broadcast");
            req.setServletPath("/api/v1/teams/1/broadcast");
            assertThat(filter.shouldNotFilter(req)).isTrue();
        }

        @Test
        @DisplayName("未認証は shouldNotFilter=true（認証フィルタに委ねる）")
        void unauthenticatedIsNotFiltered() {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/teams/1/broadcast");
            req.setServletPath("/api/v1/teams/1/broadcast");
            // 未認証 → shouldNotFilter=true
            assertThat(filter.shouldNotFilter(req)).isTrue();
        }

        @Test
        @DisplayName("broadcast 以外のパスはフィルタ対象外")
        void nonBroadcastPathIsNotFiltered() {
            authenticateAs("user-alice");
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/teams/1/events");
            req.setServletPath("/api/v1/teams/1/events");
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
            authenticateAs("user-alice");
            ObjectProvider<ValkeyRateLimiter> emptyProvider = mock(ObjectProvider.class);
            when(emptyProvider.getIfAvailable()).thenReturn(null);
            BroadcastRateLimitFilter beanlessFilter = new BroadcastRateLimitFilter(emptyProvider);

            MockHttpServletRequest req = postBroadcast("teams", "1", "10.0.7.1");
            MockHttpServletResponse response = new MockHttpServletResponse();
            beanlessFilter.doFilter(req, response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        }
    }
}
