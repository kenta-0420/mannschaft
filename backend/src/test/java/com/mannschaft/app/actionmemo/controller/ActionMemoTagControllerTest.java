package com.mannschaft.app.actionmemo.controller;

import com.mannschaft.app.actionmemo.ActionMemoRateLimitFilter;
import com.mannschaft.app.common.ratelimit.RateLimitResult;
import com.mannschaft.app.common.ratelimit.ValkeyRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ActionMemoRateLimitFilter} のタグ作成レートリミット検証（Phase 4）。
 *
 * <p>設計書 §6 に従い以下を検証する:</p>
 * <ul>
 *   <li>{@code POST /api/v1/action-memo-tags}: 20 回まで成功、21 回目で 429</li>
 * </ul>
 *
 * <p><b>実装アプローチ（Valkey 化第一陣）</b>: {@code ActionMemoControllerTest} と同じく
 * Filter を直接呼び出して検証する。{@link ValkeyRateLimiter} はモックし、簡易カウンタで
 * N 回目まで allowed / N+1 回目 denied を再現する。実カウント検証は
 * {@code ValkeyRateLimiterIntegrationTest}（Testcontainers 実 Redis）の責務に移った。</p>
 */
@DisplayName("ActionMemoTagController レートリミット検証")
class ActionMemoTagControllerTest {

    private ActionMemoRateLimitFilter filter;
    /** (zone|key) ごとの呼び出し回数。モックがこの値と limit を比較して allowed を決める。 */
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        counters.clear();
        ValkeyRateLimiter rateLimiter = mock(ValkeyRateLimiter.class);
        when(rateLimiter.tryConsume(anyString(), anyString(), anyInt(), any(Duration.class)))
                .thenAnswer(inv -> {
                    String zone = inv.getArgument(0);
                    String key = inv.getArgument(1);
                    int limit = inv.getArgument(2);
                    long count = counters
                            .computeIfAbsent(zone + "|" + key, k -> new AtomicLong())
                            .incrementAndGet();
                    return new RateLimitResult(
                            count <= limit, limit, Math.max(0, limit - count), 1_750_000_020L, 20L);
                });
        ObjectProvider<ValkeyRateLimiter> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(rateLimiter);
        filter = new ActionMemoRateLimitFilter(provider);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("200", null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("POST /api/v1/action-memo-tags: 20 回まで成功、21 回目で 429")
    void createTag_rateLimit20PerMinute() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 20; i++) {
            MockHttpServletRequest request = buildRequest("/api/v1/action-memo-tags", "POST");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        }
        verify(chain, times(20)).doFilter(any(), any());

        // 21 回目: 429
        MockHttpServletRequest request = buildRequest("/api/v1/action-memo-tags", "POST");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(429);
        // Retry-After はモック結果の retryAfterSeconds（ウィンドウ残秒）が返る
        assertThat(response.getHeader("Retry-After")).isEqualTo("20");
    }

    @Test
    @DisplayName("GET /api/v1/action-memo-tags: レートリミット対象外（Filter 透過）")
    void getTags_shouldNotFilter() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = buildRequest("/api/v1/action-memo-tags", "GET");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    private MockHttpServletRequest buildRequest(String path, String method) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServletPath(path);
        request.setMethod(method);
        request.setRemoteAddr("127.0.0.1");
        return request;
    }
}
