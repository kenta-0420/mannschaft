package com.mannschaft.app.pointcard.controller;

import com.mannschaft.app.common.ratelimit.RateLimitResult;
import com.mannschaft.app.common.ratelimit.ValkeyRateLimiter;
import com.mannschaft.app.pointcard.filter.PointCardRateLimitFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
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
 * {@link PointCardRateLimitFilter} のレートリミット検証（Valkey 化後）。
 *
 * <p>{@link ValkeyRateLimiter} はモックし、各エンドポイントの (zone, limit, window) 宣言と
 * 429 応答・§4.3 ヘッダーを検証する。</p>
 */
@DisplayName("PointCardRateLimitFilter レートリミット検証")
class PointCardRateLimitFilterTest {

    private static final long RESET_EPOCH = 1_750_000_020L;
    private static final long RETRY_AFTER = 20L;

    private PointCardRateLimitFilter filter;
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
        filter = new PointCardRateLimitFilter(provider);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("100", null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
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

    @Test
    @DisplayName("GET /api/v1/point-cards/providers: 60 回まで成功、61 回目で 429")
    void providers_rateLimit60PerMinute() throws Exception {
        for (int i = 0; i < 60; i++) {
            MockHttpServletResponse response = invoke(buildRequest("/api/v1/point-cards/providers", "GET"));
            assertThat(response.getStatus())
                    .as("リクエスト %d 回目は200を期待", i + 1)
                    .isEqualTo(HttpServletResponse.SC_OK);
        }

        MockHttpServletResponse overLimit = invoke(buildRequest("/api/v1/point-cards/providers", "GET"));
        assertThat(overLimit.getStatus()).isEqualTo(429);
        assertThat(overLimit.getHeader("Retry-After")).isEqualTo(String.valueOf(RETRY_AFTER));
        assertThat(overLimit.getHeader("X-RateLimit-Limit")).isEqualTo("60");

        verify(rateLimiter, atLeastOnce()).tryConsume(
                eq("pointcard:PROVIDERS"), anyString(), eq(60), eq(Duration.ofMinutes(1)));
    }

    @Test
    @DisplayName("PUT /api/v1/point-cards/settings: 10 回まで成功、11 回目で 429")
    void settings_rateLimit10PerHour() throws Exception {
        for (int i = 0; i < 10; i++) {
            MockHttpServletResponse response = invoke(buildRequest("/api/v1/point-cards/settings", "PUT"));
            assertThat(response.getStatus())
                    .as("リクエスト %d 回目は200を期待", i + 1)
                    .isEqualTo(HttpServletResponse.SC_OK);
        }

        MockHttpServletResponse overLimit = invoke(buildRequest("/api/v1/point-cards/settings", "PUT"));
        assertThat(overLimit.getStatus()).isEqualTo(429);
        assertThat(overLimit.getHeader("X-RateLimit-Limit")).isEqualTo("10");

        verify(rateLimiter, atLeastOnce()).tryConsume(
                eq("pointcard:SETTINGS_PUT"), anyString(), eq(10), eq(Duration.ofHours(1)));
    }

    @Test
    @DisplayName("GET /api/v1/point-cards/settings: 透過する（このフィルタの責務外）")
    void getSettings_notFilteredByThisFilter() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = buildRequest("/api/v1/point-cards/settings", "GET");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    @DisplayName("対象外パス (例: /api/v1/other) は透過する")
    void unrelatedPath_isTransparent() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = buildRequest("/api/v1/other", "POST");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    @DisplayName("POST /api/v1/point-cards (カード作成): 30 回まで成功、31 回目で 429")
    void createCard_rateLimit30PerHour() throws Exception {
        for (int i = 0; i < 30; i++) {
            assertThat(invoke(buildRequest("/api/v1/point-cards", "POST")).getStatus())
                    .as("リクエスト %d 回目は200を期待", i + 1)
                    .isEqualTo(HttpServletResponse.SC_OK);
        }

        MockHttpServletResponse overLimit = invoke(buildRequest("/api/v1/point-cards", "POST"));
        assertThat(overLimit.getStatus()).isEqualTo(429);
        verify(rateLimiter, atLeastOnce()).tryConsume(
                eq("pointcard:CREATE_CARD"), anyString(), eq(30), eq(Duration.ofHours(1)));
    }

    @Test
    @DisplayName("GET /api/v1/point-cards/{id}: 120 回まで成功、121 回目で 429")
    void getCardDetail_rateLimit120PerMinute() throws Exception {
        String cardId = "01956c00-0000-7000-8000-000000000001";

        for (int i = 0; i < 120; i++) {
            assertThat(invoke(buildRequest("/api/v1/point-cards/" + cardId, "GET")).getStatus())
                    .as("リクエスト %d 回目は200を期待", i + 1)
                    .isEqualTo(HttpServletResponse.SC_OK);
        }

        MockHttpServletResponse overLimit = invoke(buildRequest("/api/v1/point-cards/" + cardId, "GET"));
        assertThat(overLimit.getStatus()).isEqualTo(429);
        verify(rateLimiter, atLeastOnce()).tryConsume(
                eq("pointcard:GET_DETAIL"), anyString(), eq(120), eq(Duration.ofMinutes(1)));
    }

    @Test
    @DisplayName("POST /api/v1/point-cards/{id}/used: パターンが /{id} より先に評価されることを確認")
    void recordUsed_pathMoreSpecificThanDetail() throws Exception {
        String cardId = "01956c00-0000-7000-8000-000000000002";
        // 600/h なので 30 回程度では制限に達しないことだけ確認（パターン誤判定検出）
        for (int i = 0; i < 30; i++) {
            assertThat(invoke(buildRequest("/api/v1/point-cards/" + cardId + "/used", "POST")).getStatus())
                    .isEqualTo(HttpServletResponse.SC_OK);
        }
        verify(rateLimiter, atLeastOnce()).tryConsume(
                eq("pointcard:RECORD_USED"), anyString(), eq(600), eq(Duration.ofHours(1)));
    }

    @Test
    @DisplayName("未認証ユーザー: IP ベースで GET /providers のレート制限が動作する")
    void unauthenticated_ipBasedRateLimit() throws Exception {
        SecurityContextHolder.clearContext();
        counters.clear();

        for (int i = 0; i < 60; i++) {
            MockHttpServletRequest request = buildRequest("/api/v1/point-cards/providers", "GET");
            request.setRemoteAddr("203.0.113.42");
            assertThat(invoke(request).getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        }

        MockHttpServletRequest request = buildRequest("/api/v1/point-cards/providers", "GET");
        request.setRemoteAddr("203.0.113.42");
        assertThat(invoke(request).getStatus()).isEqualTo(429);

        verify(rateLimiter, atLeastOnce()).tryConsume(
                eq("pointcard:PROVIDERS"), eq("ip:203.0.113.42"), eq(60), eq(Duration.ofMinutes(1)));
    }

    private MockHttpServletRequest buildRequest(String path, String method) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServletPath(path);
        request.setMethod(method);
        request.setRemoteAddr("127.0.0.1");
        return request;
    }
}
