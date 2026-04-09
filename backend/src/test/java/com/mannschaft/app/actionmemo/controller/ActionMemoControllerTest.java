package com.mannschaft.app.actionmemo.controller;

import com.mannschaft.app.actionmemo.ActionMemoRateLimitFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link ActionMemoRateLimitFilter} のレートリミット検証。
 *
 * <p>設計書 §7.1 に従い以下を検証する（{@code POST /api/v1/action-memos} について）:</p>
 * <ul>
 *   <li>60 回まで成功（chain.doFilter が呼ばれる）</li>
 *   <li>61 回目で 429 Too Many Requests</li>
 * </ul>
 *
 * <p><b>実装アプローチ</b>: {@code @WebMvcTest} でフルスタックを起動するのではなく、
 * Filter を直接呼び出して Bucket4j のトークン消費を検証する。JwtAuthenticationFilter や
 * Spring Security 全体を上げる必要がなく、レートリミットのロジック単独で完結する。</p>
 */
@DisplayName("ActionMemoRateLimitFilter レートリミット検証")
class ActionMemoControllerTest {

    private ActionMemoRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new ActionMemoRateLimitFilter();
        // 認証済みユーザーをセット（Filter が userId ベースのキーを使うため）
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("100", null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("POST /api/v1/action-memos: 60 回まで成功、61 回目で 429")
    void createMemo_rateLimit60PerMinute() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 60; i++) {
            MockHttpServletRequest request = buildRequest("/api/v1/action-memos", "POST");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        }
        verify(chain, times(60)).doFilter(any(), any());

        // 61 回目: 429
        MockHttpServletRequest request = buildRequest("/api/v1/action-memos", "POST");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("60");
    }

    @Test
    @DisplayName("PATCH /api/v1/action-memo-settings: 10 回まで成功、11 回目で 429")
    void updateSettings_rateLimit10PerMinute() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 10; i++) {
            MockHttpServletRequest request = buildRequest("/api/v1/action-memo-settings", "PATCH");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        }

        // 11 回目: 429
        MockHttpServletRequest request = buildRequest("/api/v1/action-memo-settings", "PATCH");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(429);
    }

    @Test
    @DisplayName("POST /api/v1/action-memos/publish-daily: 5 回まで成功、6 回目で 429")
    void publishDaily_rateLimit5PerMinute() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest request = buildRequest("/api/v1/action-memos/publish-daily", "POST");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        }

        // 6 回目: 429
        MockHttpServletRequest request = buildRequest("/api/v1/action-memos/publish-daily", "POST");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("60");
    }

    @Test
    @DisplayName("対象外エンドポイント: Filter は透過する（GET /action-memos など）")
    void shouldNotFilter_forNonTrackedEndpoints() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = buildRequest("/api/v1/action-memos", "GET");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        // shouldNotFilter=true のため doFilterInternal は呼ばれず、chain.doFilter が実行される
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
