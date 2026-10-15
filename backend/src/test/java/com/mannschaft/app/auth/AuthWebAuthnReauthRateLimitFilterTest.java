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
 * {@link AuthWebAuthnReauthRateLimitFilter} のユニットテスト（Valkey 化後）。
 */
class AuthWebAuthnReauthRateLimitFilterTest {

    private static final long RESET_EPOCH = 1_750_000_020L;
    private static final long RETRY_AFTER = 20L;

    private AuthWebAuthnReauthRateLimitFilter filter;
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
        filter = new AuthWebAuthnReauthRateLimitFilter(provider);
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
    @DisplayName("POST /reauthenticate-begin — 10 req/分")
    class ReauthBegin {

        @Test
        @DisplayName("10 回通過、11 回目で 429 / 標準ヘッダー付与")
        void exceedsLimit() throws Exception {
            String ip = "10.0.0.1";
            for (int i = 0; i < 10; i++) {
                assertThat(invoke(request("POST", "/api/v1/auth/webauthn/reauthenticate-begin", ip)).getStatus())
                        .isEqualTo(HttpStatus.OK.value());
            }
            MockHttpServletResponse over = invoke(request("POST", "/api/v1/auth/webauthn/reauthenticate-begin", ip));
            assertThat(over.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
            assertThat(over.getHeader("X-RateLimit-Limit")).isEqualTo("10");
            verify(rateLimiter, atLeastOnce()).tryConsume(
                    eq("webauthn-reauth:BEGIN"), eq("ip:" + ip), eq(10), eq(Duration.ofMinutes(1)));
        }
    }

    @Nested
    @DisplayName("POST /reauthenticate-complete — 10 req/分")
    class ReauthComplete {

        @Test
        @DisplayName("10 回通過、11 回目で 429")
        void exceedsLimit() throws Exception {
            String ip = "10.0.1.1";
            for (int i = 0; i < 10; i++) {
                assertThat(invoke(request("POST", "/api/v1/auth/webauthn/reauthenticate-complete", ip)).getStatus())
                        .isEqualTo(HttpStatus.OK.value());
            }
            assertThat(invoke(request("POST", "/api/v1/auth/webauthn/reauthenticate-complete", ip)).getStatus())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
            verify(rateLimiter, atLeastOnce()).tryConsume(
                    eq("webauthn-reauth:COMPLETE"), eq("ip:" + ip), eq(10), eq(Duration.ofMinutes(1)));
        }
    }

    @Nested
    @DisplayName("begin と complete のゾーン分離")
    class ZoneIsolation {

        @Test
        @DisplayName("begin を使い切っても complete は独立して通る")
        void endpointsAreIndependent() throws Exception {
            String ip = "10.0.2.1";
            for (int i = 0; i < 10; i++) {
                invoke(request("POST", "/api/v1/auth/webauthn/reauthenticate-begin", ip));
            }
            assertThat(invoke(request("POST", "/api/v1/auth/webauthn/reauthenticate-begin", ip)).getStatus())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
            // complete は別ゾーン
            assertThat(invoke(request("POST", "/api/v1/auth/webauthn/reauthenticate-complete", ip)).getStatus())
                    .isEqualTo(HttpStatus.OK.value());
        }
    }

    @Nested
    @DisplayName("対象外パスは透過")
    class SkipNonTarget {

        @Test
        @DisplayName("GET リクエストは透過する")
        void getRequestSkipped() throws Exception {
            assertThat(filter.shouldNotFilter(
                    request("GET", "/api/v1/auth/webauthn/reauthenticate-begin", "1.2.3.4")))
                    .isTrue();
        }

        @Test
        @DisplayName("ログイン用パスは透過する")
        void loginPathSkipped() throws Exception {
            assertThat(filter.shouldNotFilter(
                    request("POST", "/api/v1/auth/webauthn/login-begin", "1.2.3.4")))
                    .isTrue();
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
            AuthWebAuthnReauthRateLimitFilter beanlessFilter = new AuthWebAuthnReauthRateLimitFilter(emptyProvider);

            MockHttpServletResponse response = new MockHttpServletResponse();
            beanlessFilter.doFilter(
                    request("POST", "/api/v1/auth/webauthn/reauthenticate-begin", "10.0.7.1"),
                    response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        }
    }
}
