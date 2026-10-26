package com.mannschaft.app.visibility;

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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link VisibilityTemplateRateLimitFilter} のユニットテスト（Valkey 化後）。
 *
 * <p>時間ウィンドウのみ。未認証ユーザー（userId null）透過の挙動も検証する。</p>
 */
class VisibilityTemplateRateLimitFilterTest {

    private static final long RESET_EPOCH = 1_750_000_020L;
    private static final long RETRY_AFTER = 20L;

    private VisibilityTemplateRateLimitFilter filter;
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
        filter = new VisibilityTemplateRateLimitFilter(provider);
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
        req.setRequestURI(path);
        req.setRemoteAddr(ip);
        return req;
    }

    private void authenticateAs(String userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @Nested
    @DisplayName("POST /api/v1/visibility-templates — 10 req/時")
    class CreateTemplate {

        @Test
        @DisplayName("認証済みで 10 回通過、11 回目で 429 / 時間ウィンドウ宣言確認")
        void exceedsLimit() throws Exception {
            authenticateAs("user-1");
            String ip = "10.0.0.1";
            for (int i = 0; i < 10; i++) {
                assertThat(invoke(request("POST", "/api/v1/visibility-templates", ip)).getStatus())
                        .isEqualTo(HttpStatus.OK.value());
            }
            MockHttpServletResponse over = invoke(request("POST", "/api/v1/visibility-templates", ip));
            assertThat(over.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
            assertThat(over.getHeader("X-RateLimit-Limit")).isEqualTo("10");
            verify(rateLimiter, atLeastOnce()).tryConsume(
                    eq("visibility-template:CREATE_TEMPLATE"), eq("u:user-1"), eq(10), eq(Duration.ofHours(1)));
        }
    }

    @Nested
    @DisplayName("PUT /api/v1/visibility-templates/{id} — 30 req/時")
    class UpdateTemplate {

        @Test
        @DisplayName("30 回通過、31 回目で 429 / 時間ウィンドウ宣言確認")
        void exceedsLimit() throws Exception {
            authenticateAs("user-2");
            String ip = "10.0.1.1";
            String path = "/api/v1/visibility-templates/01956c00-0000-7000-8000-000000000001";
            for (int i = 0; i < 30; i++) {
                assertThat(invoke(request("PUT", path, ip)).getStatus())
                        .isEqualTo(HttpStatus.OK.value());
            }
            assertThat(invoke(request("PUT", path, ip)).getStatus())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
            verify(rateLimiter, atLeastOnce()).tryConsume(
                    eq("visibility-template:UPDATE_TEMPLATE"), eq("u:user-2"), eq(30), eq(Duration.ofHours(1)));
        }
    }

    @Nested
    @DisplayName("POST /api/v1/visibility-templates/{id}/evaluate — 100 req/時")
    class EvaluateTemplate {

        @Test
        @DisplayName("evaluate は更新より先に判定される（優先度確認）")
        void evaluateEvaluatedBeforeUpdate() throws Exception {
            authenticateAs("user-3");
            invoke(request("POST", "/api/v1/visibility-templates/01956c00-0000-7000-8000-000000000001/evaluate", "10.0.2.1"));
            verify(rateLimiter, atLeastOnce()).tryConsume(
                    eq("visibility-template:EVALUATE"), anyString(), eq(100), eq(Duration.ofHours(1)));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/visibility-templates/{id}/resolved-members — 20 req/時")
    class ResolvedMembers {

        @Test
        @DisplayName("20 回通過、21 回目で 429")
        void exceedsLimit() throws Exception {
            authenticateAs("user-4");
            String ip = "10.0.3.1";
            String path = "/api/v1/visibility-templates/01956c00-0000-7000-8000-000000000002/resolved-members";
            for (int i = 0; i < 20; i++) {
                assertThat(invoke(request("GET", path, ip)).getStatus())
                        .isEqualTo(HttpStatus.OK.value());
            }
            assertThat(invoke(request("GET", path, ip)).getStatus())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
            verify(rateLimiter, atLeastOnce()).tryConsume(
                    eq("visibility-template:RESOLVED_MEMBERS"), eq("u:user-4"), eq(20), eq(Duration.ofHours(1)));
        }
    }

    @Nested
    @DisplayName("未認証ユーザーは透過（旧実装の挙動維持）")
    class UnauthenticatedPassThrough {

        @Test
        @DisplayName("未認証リクエストは ValkeyRateLimiter を呼ばず透過する")
        void unauthenticatedPassThrough() throws Exception {
            // 認証なし（anonymousUser扱い）
            MockHttpServletResponse response = invoke(
                    request("POST", "/api/v1/visibility-templates", "10.0.4.1"));
            // shouldNotFilter=false（パスは対象）だが resolveRule が null を返すため透過
            assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
            verify(rateLimiter, never()).tryConsume(anyString(), anyString(), anyInt(), any());
        }
    }

    @Nested
    @DisplayName("ValkeyRateLimiter Bean 不在")
    class LimiterBeanAbsent {

        @Test
        @DisplayName("Bean 不在時は素通しする")
        @SuppressWarnings("unchecked")
        void passesThroughWhenLimiterUnavailable() throws Exception {
            authenticateAs("user-5");
            ObjectProvider<ValkeyRateLimiter> emptyProvider = mock(ObjectProvider.class);
            when(emptyProvider.getIfAvailable()).thenReturn(null);
            VisibilityTemplateRateLimitFilter beanlessFilter =
                    new VisibilityTemplateRateLimitFilter(emptyProvider);

            MockHttpServletResponse response = new MockHttpServletResponse();
            beanlessFilter.doFilter(
                    request("POST", "/api/v1/visibility-templates", "10.0.7.1"),
                    response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        }
    }
}
