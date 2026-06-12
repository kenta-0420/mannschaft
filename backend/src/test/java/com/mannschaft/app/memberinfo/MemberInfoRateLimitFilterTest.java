package com.mannschaft.app.memberinfo;

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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MemberInfoRateLimitFilter} のユニットテスト（Valkey 化後）。
 */
class MemberInfoRateLimitFilterTest {

    private static final long RESET_EPOCH = 1_750_000_020L;
    private static final long RETRY_AFTER = 20L;

    private MemberInfoRateLimitFilter filter;
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
        filter = new MemberInfoRateLimitFilter(provider);
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

    private MockHttpServletRequest request(String method, String path, String ip) {
        MockHttpServletRequest req = new MockHttpServletRequest(method, path);
        req.setServletPath(path);
        req.setRemoteAddr(ip);
        return req;
    }

    @Nested
    @DisplayName("PUT /api/v1/teams/{teamId}/member-info/responses/me — 10 req/分")
    class UpsertResponses {

        @Test
        @DisplayName("10 回通過、11 回目で 429 / 標準ヘッダー付与")
        void exceedsLimit() throws Exception {
            String ip = "10.0.0.1";
            String path = "/api/v1/teams/team-abc/member-info/responses/me";
            for (int i = 0; i < 10; i++) {
                assertThat(invoke(request("PUT", path, ip)).getStatus())
                        .isEqualTo(HttpStatus.OK.value());
            }
            MockHttpServletResponse over = invoke(request("PUT", path, ip));
            assertThat(over.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
            assertThat(over.getHeader("X-RateLimit-Limit")).isEqualTo("10");
            assertThat(over.getHeader("Retry-After")).isEqualTo(String.valueOf(RETRY_AFTER));
            verify(rateLimiter, atLeastOnce()).tryConsume(
                    eq("memberinfo:UPSERT_RESPONSES"), eq("ip:" + ip), eq(10), eq(Duration.ofMinutes(1)));
        }

        @Test
        @DisplayName("§4.3: 通過時にも X-RateLimit-* ヘッダーが付与される")
        void standardHeadersOnSuccess() throws Exception {
            String path = "/api/v1/teams/team-xyz/member-info/responses/me";
            MockHttpServletResponse response = invoke(request("PUT", path, "10.0.0.8"));
            assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
            assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("10");
            assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("9");
        }

        @Test
        @DisplayName("認証済みユーザーは u:{userId} キーでカウントされる")
        void authenticatedUserKey() throws Exception {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken("user-alice", "n/a",
                            List.of(new SimpleGrantedAuthority("ROLE_USER"))));
            invoke(request("PUT", "/api/v1/teams/team-1/member-info/responses/me", "10.0.0.9"));
            verify(rateLimiter, atLeastOnce()).tryConsume(
                    eq("memberinfo:UPSERT_RESPONSES"), eq("u:user-alice"), anyInt(), any());
        }
    }

    @Nested
    @DisplayName("対象外パスは透過")
    class SkipNonTarget {

        @Test
        @DisplayName("GET リクエストは透過する")
        void getRequestSkipped() throws Exception {
            FilterChain chain = mock(FilterChain.class);
            MockHttpServletRequest req = request("GET", "/api/v1/teams/1/member-info/responses/me", "1.2.3.4");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(req, response, chain);
            verify(chain, times(1)).doFilter(any(), any());
        }

        @Test
        @DisplayName("対象外パスは透過する")
        void unrelatedPathSkipped() throws Exception {
            FilterChain chain = mock(FilterChain.class);
            MockHttpServletRequest req = request("PUT", "/api/v1/other", "1.2.3.4");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(req, response, chain);
            verify(chain, times(1)).doFilter(any(), any());
        }
    }

    @Nested
    @DisplayName("ValkeyRateLimiter Bean 不在")
    class LimiterBeanAbsent {

        @Test
        @DisplayName("Bean 不在時は素通しする")
        @SuppressWarnings("unchecked")
        void passesThroughWhenLimiterUnavailable() throws Exception {
            ObjectProvider<ValkeyRateLimiter> emptyProvider = mock(ObjectProvider.class);
            when(emptyProvider.getIfAvailable()).thenReturn(null);
            MemberInfoRateLimitFilter beanlessFilter = new MemberInfoRateLimitFilter(emptyProvider);

            MockHttpServletResponse response = new MockHttpServletResponse();
            beanlessFilter.doFilter(
                    request("PUT", "/api/v1/teams/1/member-info/responses/me", "10.0.7.1"),
                    response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        }
    }
}
