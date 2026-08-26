package com.mannschaft.app.favorite.filter;

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
 * {@link FavoriteRateLimitFilter} のユニットテスト（Valkey 化後）。
 *
 * <p>分ウィンドウ / 時間ウィンドウ混在の zone+window 宣言が正確であることを検証する。</p>
 */
class FavoriteRateLimitFilterTest {

    private static final long RESET_EPOCH = 1_750_000_020L;
    private static final long RETRY_AFTER = 20L;

    private FavoriteRateLimitFilter filter;
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
        filter = new FavoriteRateLimitFilter(provider);
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
    @DisplayName("GET /api/v1/me/favorites — 120 req/分")
    class ListFavorites {

        @Test
        @DisplayName("120 回通過、121 回目で 429 / 分ウィンドウ宣言確認")
        void exceedsLimit() throws Exception {
            String ip = "10.0.0.1";
            for (int i = 0; i < 120; i++) {
                assertThat(invoke(request("GET", "/api/v1/me/favorites", ip)).getStatus())
                        .isEqualTo(HttpStatus.OK.value());
            }
            assertThat(invoke(request("GET", "/api/v1/me/favorites", ip)).getStatus())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
            verify(rateLimiter, atLeastOnce()).tryConsume(
                    eq("favorite:LIST"), anyString(), eq(120), eq(Duration.ofMinutes(1)));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/me/favorites/check — 240 req/分")
    class CheckFavorite {

        @Test
        @DisplayName("240 回通過、241 回目で 429 / 分ウィンドウ宣言確認")
        void exceedsLimit() throws Exception {
            String ip = "10.0.1.1";
            for (int i = 0; i < 240; i++) {
                assertThat(invoke(request("GET", "/api/v1/me/favorites/check", ip)).getStatus())
                        .isEqualTo(HttpStatus.OK.value());
            }
            assertThat(invoke(request("GET", "/api/v1/me/favorites/check", ip)).getStatus())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
            verify(rateLimiter, atLeastOnce()).tryConsume(
                    eq("favorite:CHECK"), anyString(), eq(240), eq(Duration.ofMinutes(1)));
        }
    }

    @Nested
    @DisplayName("POST /api/v1/me/favorites — 30 req/時")
    class AddFavorite {

        @Test
        @DisplayName("30 回通過、31 回目で 429 / 時間ウィンドウ宣言確認")
        void exceedsLimit() throws Exception {
            String ip = "10.0.2.1";
            for (int i = 0; i < 30; i++) {
                assertThat(invoke(request("POST", "/api/v1/me/favorites", ip)).getStatus())
                        .isEqualTo(HttpStatus.OK.value());
            }
            assertThat(invoke(request("POST", "/api/v1/me/favorites", ip)).getStatus())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
            verify(rateLimiter, atLeastOnce()).tryConsume(
                    eq("favorite:ADD"), anyString(), eq(30), eq(Duration.ofHours(1)));
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/me/favorites/{id} — 60 req/時")
    class DeleteFavorite {

        @Test
        @DisplayName("60 回通過、61 回目で 429 / 時間ウィンドウ宣言確認")
        void exceedsLimit() throws Exception {
            String ip = "10.0.3.1";
            String path = "/api/v1/me/favorites/01956c00-0000-7000-8000-000000000001";
            for (int i = 0; i < 60; i++) {
                assertThat(invoke(request("DELETE", path, ip)).getStatus())
                        .isEqualTo(HttpStatus.OK.value());
            }
            assertThat(invoke(request("DELETE", path, ip)).getStatus())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
            verify(rateLimiter, atLeastOnce()).tryConsume(
                    eq("favorite:DELETE"), anyString(), eq(60), eq(Duration.ofHours(1)));
        }
    }

    @Nested
    @DisplayName("PATCH /api/v1/me/favorites/reorder — 30 req/時")
    class ReorderFavorites {

        @Test
        @DisplayName("30 回通過、31 回目で 429 / 時間ウィンドウ宣言確認")
        void exceedsLimit() throws Exception {
            String ip = "10.0.4.1";
            for (int i = 0; i < 30; i++) {
                assertThat(invoke(request("PATCH", "/api/v1/me/favorites/reorder", ip)).getStatus())
                        .isEqualTo(HttpStatus.OK.value());
            }
            assertThat(invoke(request("PATCH", "/api/v1/me/favorites/reorder", ip)).getStatus())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
            verify(rateLimiter, atLeastOnce()).tryConsume(
                    eq("favorite:REORDER"), anyString(), eq(30), eq(Duration.ofHours(1)));
        }
    }

    @Nested
    @DisplayName("ゾーン分離と ValkeyRateLimiter Bean 不在")
    class Misc {

        @Test
        @DisplayName("ValkeyRateLimiter が解決できない場合は素通しする")
        @SuppressWarnings("unchecked")
        void passesThroughWhenLimiterUnavailable() throws Exception {
            ObjectProvider<ValkeyRateLimiter> emptyProvider = mock(ObjectProvider.class);
            when(emptyProvider.getIfAvailable()).thenReturn(null);
            FavoriteRateLimitFilter beanlessFilter = new FavoriteRateLimitFilter(emptyProvider);

            MockHttpServletResponse response = new MockHttpServletResponse();
            beanlessFilter.doFilter(
                    request("GET", "/api/v1/me/favorites", "10.0.7.1"),
                    response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        }
    }
}
