package com.mannschaft.app.pointcard.controller;

import com.mannschaft.app.pointcard.filter.PointCardRateLimitFilter;
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
 * {@link PointCardRateLimitFilter} のレートリミット検証。
 *
 * <p>設計書 §9.5 に従い以下を検証する:
 * <ul>
 *   <li>GET /api/v1/point-cards/providers: 60 回まで成功、61 回目で 429</li>
 *   <li>PUT /api/v1/point-cards/settings: 10 回まで成功、11 回目で 429</li>
 *   <li>対象外パスは透過する</li>
 *   <li>未認証 IP ベースでも動作する</li>
 * </ul>
 */
@DisplayName("PointCardRateLimitFilter レートリミット検証")
class PointCardRateLimitFilterTest {

    private PointCardRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new PointCardRateLimitFilter();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("100", null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("GET /api/v1/point-cards/providers: 60 回まで成功、61 回目で 429")
    void providers_rateLimit60PerMinute() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 60; i++) {
            MockHttpServletRequest request = buildRequest("/api/v1/point-cards/providers", "GET");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus())
                    .as("リクエスト %d 回目は200を期待", i + 1)
                    .isEqualTo(HttpServletResponse.SC_OK);
        }
        verify(chain, times(60)).doFilter(any(), any());

        MockHttpServletRequest request = buildRequest("/api/v1/point-cards/providers", "GET");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("60");
    }

    @Test
    @DisplayName("PUT /api/v1/point-cards/settings: 10 回まで成功、11 回目で 429")
    void settings_rateLimit10PerHour() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 10; i++) {
            MockHttpServletRequest request = buildRequest("/api/v1/point-cards/settings", "PUT");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus())
                    .as("リクエスト %d 回目は200を期待", i + 1)
                    .isEqualTo(HttpServletResponse.SC_OK);
        }
        verify(chain, times(10)).doFilter(any(), any());

        MockHttpServletRequest request = buildRequest("/api/v1/point-cards/settings", "PUT");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("3600");
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
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 30; i++) {
            MockHttpServletRequest request = buildRequest("/api/v1/point-cards", "POST");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus())
                    .as("リクエスト %d 回目は200を期待", i + 1)
                    .isEqualTo(HttpServletResponse.SC_OK);
        }
        verify(chain, times(30)).doFilter(any(), any());

        MockHttpServletRequest request = buildRequest("/api/v1/point-cards", "POST");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("3600");
    }

    @Test
    @DisplayName("GET /api/v1/point-cards/{id}: 120 回まで成功、121 回目で 429")
    void getCardDetail_rateLimit120PerMinute() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        String cardId = "01956c00-0000-7000-8000-000000000001";

        for (int i = 0; i < 120; i++) {
            MockHttpServletRequest request = buildRequest("/api/v1/point-cards/" + cardId, "GET");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus())
                    .as("リクエスト %d 回目は200を期待", i + 1)
                    .isEqualTo(HttpServletResponse.SC_OK);
        }
        verify(chain, times(120)).doFilter(any(), any());

        MockHttpServletRequest request = buildRequest("/api/v1/point-cards/" + cardId, "GET");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("60");
    }

    @Test
    @DisplayName("POST /api/v1/point-cards/{id}/used: パターンが /{id} より先に評価されることを確認")
    void recordUsed_pathMoreSpecificThanDetail() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        String cardId = "01956c00-0000-7000-8000-000000000002";
        // 600/h なので 30 回程度では制限に達しないことだけ確認（パターン誤判定検出）
        for (int i = 0; i < 30; i++) {
            MockHttpServletRequest request = buildRequest("/api/v1/point-cards/" + cardId + "/used", "POST");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        }
        verify(chain, times(30)).doFilter(any(), any());
    }

    @Test
    @DisplayName("未認証ユーザー: IP ベースで GET /providers のレート制限が動作する")
    void unauthenticated_ipBasedRateLimit() throws Exception {
        SecurityContextHolder.clearContext();
        FilterChain chain = mock(FilterChain.class);
        filter = new PointCardRateLimitFilter();

        for (int i = 0; i < 60; i++) {
            MockHttpServletRequest request = buildRequest("/api/v1/point-cards/providers", "GET");
            request.setRemoteAddr("203.0.113.42");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        }

        MockHttpServletRequest request = buildRequest("/api/v1/point-cards/providers", "GET");
        request.setRemoteAddr("203.0.113.42");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(429);
    }

    private MockHttpServletRequest buildRequest(String path, String method) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServletPath(path);
        request.setMethod(method);
        request.setRemoteAddr("127.0.0.1");
        return request;
    }
}
