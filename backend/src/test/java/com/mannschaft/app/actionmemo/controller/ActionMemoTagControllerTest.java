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
 * {@link ActionMemoRateLimitFilter} のタグ作成レートリミット検証（Phase 4）。
 *
 * <p>設計書 §6 に従い以下を検証する:</p>
 * <ul>
 *   <li>{@code POST /api/v1/action-memo-tags}: 20 回まで成功、21 回目で 429</li>
 * </ul>
 *
 * <p><b>実装アプローチ</b>: {@code ActionMemoControllerTest} と同じく Filter を直接呼び出して
 * Bucket4j のトークン消費を検証する。</p>
 */
@DisplayName("ActionMemoTagController レートリミット検証")
class ActionMemoTagControllerTest {

    private ActionMemoRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new ActionMemoRateLimitFilter();
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
        assertThat(response.getHeader("Retry-After")).isEqualTo("60");
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
