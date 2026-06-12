package com.mannschaft.app.auth;

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
 * {@link AuditLogRateLimitFilter} のユニットテスト（Valkey 化後）。
 */
class AuditLogRateLimitFilterTest {

    private static final long RESET_EPOCH = 1_750_000_020L;
    private static final long RETRY_AFTER = 20L;

    private AuditLogRateLimitFilter filter;
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
        filter = new AuditLogRateLimitFilter(provider);
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

    private void authenticateAs(String userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @Nested
    @DisplayName("GET /api/v1/admin/audit-logs — 60 req/分")
    class AdminAuditLogs {

        @Test
        @DisplayName("60 回まで通過、61 回目で 429 / 標準ヘッダー付与")
        void exceedsLimit() throws Exception {
            String ip = "10.0.0.1";
            for (int i = 0; i < 60; i++) {
                assertThat(invoke(request("GET", "/api/v1/admin/audit-logs", ip)).getStatus())
                        .isEqualTo(HttpStatus.OK.value());
            }
            MockHttpServletResponse over = invoke(request("GET", "/api/v1/admin/audit-logs", ip));
            assertThat(over.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
            assertThat(over.getHeader("X-RateLimit-Limit")).isEqualTo("60");
            verify(rateLimiter, atLeastOnce()).tryConsume(
                    eq("audit-log:ADMIN_AUDIT_LOGS"), eq("ip:" + ip), eq(60), eq(Duration.ofMinutes(1)));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/users/me/audit-logs — 30 req/分")
    class MyAuditLogs {

        @Test
        @DisplayName("30 回まで通過、31 回目で 429")
        void exceedsLimit() throws Exception {
            String ip = "10.0.1.1";
            for (int i = 0; i < 30; i++) {
                assertThat(invoke(request("GET", "/api/v1/users/me/audit-logs", ip)).getStatus())
                        .isEqualTo(HttpStatus.OK.value());
            }
            assertThat(invoke(request("GET", "/api/v1/users/me/audit-logs", ip)).getStatus())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
            verify(rateLimiter, atLeastOnce()).tryConsume(
                    eq("audit-log:MY_AUDIT_LOGS"), eq("ip:" + ip), eq(30), eq(Duration.ofMinutes(1)));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/teams/{teamId}/audit-logs — ワイルドカード 30 req/分")
    class TeamAuditLogs {

        @Test
        @DisplayName("ワイルドカードパスで 30 回通過、31 回目で 429")
        void exceedsLimitWithWildcard() throws Exception {
            String ip = "10.0.2.1";
            String path = "/api/v1/teams/123/audit-logs";
            for (int i = 0; i < 30; i++) {
                assertThat(invoke(request("GET", path, ip)).getStatus())
                        .isEqualTo(HttpStatus.OK.value());
            }
            assertThat(invoke(request("GET", path, ip)).getStatus())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
            verify(rateLimiter, atLeastOnce()).tryConsume(
                    eq("audit-log:TEAM_AUDIT_LOGS"), eq("ip:" + ip), eq(30), eq(Duration.ofMinutes(1)));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/organizations/{orgId}/audit-logs — ワイルドカード 30 req/分")
    class OrgAuditLogs {

        @Test
        @DisplayName("ワイルドカードパスで 30 回通過、31 回目で 429")
        void exceedsLimitWithWildcard() throws Exception {
            String ip = "10.0.3.1";
            String path = "/api/v1/organizations/456/audit-logs";
            for (int i = 0; i < 30; i++) {
                assertThat(invoke(request("GET", path, ip)).getStatus())
                        .isEqualTo(HttpStatus.OK.value());
            }
            assertThat(invoke(request("GET", path, ip)).getStatus())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
            verify(rateLimiter, atLeastOnce()).tryConsume(
                    eq("audit-log:ORGANIZATION_AUDIT_LOGS"), eq("ip:" + ip), eq(30), eq(Duration.ofMinutes(1)));
        }
    }

    @Nested
    @DisplayName("認証済みユーザーは u:{userId} キーを使用")
    class AuthenticatedKey {

        @Test
        @DisplayName("認証済みは userId キーでカウントされる")
        void authenticatedUserKey() throws Exception {
            authenticateAs("admin-user");
            invoke(request("GET", "/api/v1/admin/audit-logs", "10.0.4.1"));
            verify(rateLimiter, atLeastOnce()).tryConsume(
                    eq("audit-log:ADMIN_AUDIT_LOGS"), eq("u:admin-user"), anyInt(), any());
        }
    }

    @Nested
    @DisplayName("エンドポイント間のゾーン分離")
    class EndpointIsolation {

        @Test
        @DisplayName("admin を使い切っても my/team/org は独立して通る")
        void endpointsAreIndependent() throws Exception {
            String ip = "10.0.5.1";
            for (int i = 0; i < 60; i++) {
                invoke(request("GET", "/api/v1/admin/audit-logs", ip));
            }
            assertThat(invoke(request("GET", "/api/v1/admin/audit-logs", ip)).getStatus())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());

            // 別ゾーンは独立
            assertThat(invoke(request("GET", "/api/v1/users/me/audit-logs", ip)).getStatus())
                    .isEqualTo(HttpStatus.OK.value());
            assertThat(invoke(request("GET", "/api/v1/teams/1/audit-logs", ip)).getStatus())
                    .isEqualTo(HttpStatus.OK.value());
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
            AuditLogRateLimitFilter beanlessFilter = new AuditLogRateLimitFilter(emptyProvider);

            MockHttpServletResponse response = new MockHttpServletResponse();
            beanlessFilter.doFilter(
                    request("GET", "/api/v1/admin/audit-logs", "10.0.7.1"),
                    response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        }
    }
}
