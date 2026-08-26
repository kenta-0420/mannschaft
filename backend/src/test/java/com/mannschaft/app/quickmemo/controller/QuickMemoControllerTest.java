package com.mannschaft.app.quickmemo.controller;

import com.mannschaft.app.common.ratelimit.RateLimitResult;
import com.mannschaft.app.common.ratelimit.ValkeyRateLimiter;
import com.mannschaft.app.quickmemo.QuickMemoRateLimitFilter;
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
 * {@link QuickMemoRateLimitFilter} のレートリミット検証（Valkey 化後）。
 *
 * <p>{@link ValkeyRateLimiter} はモックし、フィルタの責務である
 * 「エンドポイント判定 / (zone, limit, window) 宣言 / 429 応答・§4.3 ヘッダー」を検証する。
 * 旧 Bucket4j greedy refill 由来のフレークは Valkey 固定ウィンドウ移行で根治される。</p>
 */
@DisplayName("QuickMemoRateLimitFilter レートリミット検証")
class QuickMemoControllerTest {

    private static final long RESET_EPOCH = 1_750_000_020L;
    private static final long RETRY_AFTER = 20L;

    private QuickMemoRateLimitFilter filter;
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
        filter = new QuickMemoRateLimitFilter(provider);

        // 認証済みユーザーをセット
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("200", null,
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
    @DisplayName("POST /api/v1/quick-memos: 60 回まで成功、61 回目で 429")
    void createMemo_rateLimit60PerMinute() throws Exception {
        for (int i = 0; i < 60; i++) {
            MockHttpServletResponse response = invoke(buildRequest("/api/v1/quick-memos", "POST"));
            assertThat(response.getStatus())
                    .as("リクエスト %d 回目は200を期待", i + 1)
                    .isEqualTo(HttpServletResponse.SC_OK);
        }

        MockHttpServletResponse overLimit = invoke(buildRequest("/api/v1/quick-memos", "POST"));
        assertThat(overLimit.getStatus()).isEqualTo(429);
        assertThat(overLimit.getHeader("Retry-After")).isEqualTo(String.valueOf(RETRY_AFTER));
        assertThat(overLimit.getHeader("X-RateLimit-Limit")).isEqualTo("60");

        verify(rateLimiter, atLeastOnce()).tryConsume(
                eq("quickmemo:CRUD"), eq("u:200"), eq(60), eq(Duration.ofMinutes(1)));
    }

    @Test
    @DisplayName("POST /api/v1/quick-memos/*/attachments/presign: 10 回まで成功、11 回目で 429")
    void attachmentPresign_rateLimit10PerMinute() throws Exception {
        String path = "/api/v1/quick-memos/1/attachments/presign";

        for (int i = 0; i < 10; i++) {
            assertThat(invoke(buildRequest(path, "POST")).getStatus())
                    .as("リクエスト %d 回目は200を期待", i + 1)
                    .isEqualTo(HttpServletResponse.SC_OK);
        }

        MockHttpServletResponse overLimit = invoke(buildRequest(path, "POST"));
        assertThat(overLimit.getStatus()).isEqualTo(429);
        assertThat(overLimit.getHeader("X-RateLimit-Limit")).isEqualTo("10");
        verify(rateLimiter, atLeastOnce()).tryConsume(
                eq("quickmemo:ATTACHMENT"), eq("u:200"), eq(10), eq(Duration.ofMinutes(1)));
    }

    @Test
    @DisplayName("POST /api/v1/quick-memos/*/attachments/confirm: 10 回まで成功、11 回目で 429")
    void attachmentConfirm_rateLimit10PerMinute() throws Exception {
        String path = "/api/v1/quick-memos/1/attachments/confirm";

        for (int i = 0; i < 10; i++) {
            assertThat(invoke(buildRequest(path, "POST")).getStatus())
                    .as("リクエスト %d 回目は200を期待", i + 1)
                    .isEqualTo(HttpServletResponse.SC_OK);
        }

        assertThat(invoke(buildRequest(path, "POST")).getStatus()).isEqualTo(429);
    }

    @Test
    @DisplayName("POST /api/v1/me/tags: 20 回まで成功、21 回目で 429")
    void createPersonalTag_rateLimit20PerMinute() throws Exception {
        String path = "/api/v1/me/tags";

        for (int i = 0; i < 20; i++) {
            assertThat(invoke(buildRequest(path, "POST")).getStatus())
                    .as("リクエスト %d 回目は200を期待", i + 1)
                    .isEqualTo(HttpServletResponse.SC_OK);
        }

        assertThat(invoke(buildRequest(path, "POST")).getStatus()).isEqualTo(429);
        verify(rateLimiter, atLeastOnce()).tryConsume(
                eq("quickmemo:TAG"), eq("u:200"), eq(20), eq(Duration.ofMinutes(1)));
    }

    @Test
    @DisplayName("GET /api/v1/quick-memos: Filter は透過する（GETはレート制限なし）")
    void getMemos_notFiltered() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = buildRequest("/api/v1/quick-memos", "GET");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    @DisplayName("未認証ユーザー: IP ベースでレートリミットが動作する")
    void unauthenticated_ipBasedRateLimit() throws Exception {
        SecurityContextHolder.clearContext();
        counters.clear();

        for (int i = 0; i < 60; i++) {
            MockHttpServletRequest request = buildRequest("/api/v1/quick-memos", "POST");
            request.setRemoteAddr("192.168.1.1");
            assertThat(invoke(request).getStatus())
                    .as("リクエスト %d 回目は200を期待", i + 1)
                    .isEqualTo(HttpServletResponse.SC_OK);
        }

        MockHttpServletRequest request = buildRequest("/api/v1/quick-memos", "POST");
        request.setRemoteAddr("192.168.1.1");
        assertThat(invoke(request).getStatus()).isEqualTo(429);

        verify(rateLimiter, atLeastOnce()).tryConsume(
                eq("quickmemo:CRUD"), eq("ip:192.168.1.1"), eq(60), eq(Duration.ofMinutes(1)));
    }

    private MockHttpServletRequest buildRequest(String path, String method) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServletPath(path);
        request.setMethod(method);
        request.setRemoteAddr("127.0.0.1");
        return request;
    }
}
