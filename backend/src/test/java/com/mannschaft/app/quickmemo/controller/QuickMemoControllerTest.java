package com.mannschaft.app.quickmemo.controller;

import com.mannschaft.app.quickmemo.QuickMemoRateLimitFilter;
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
 * {@link QuickMemoRateLimitFilter} のレートリミット検証。
 *
 * <p>以下を検証する:</p>
 * <ul>
 *   <li>CRUD: 60 回まで成功、61 回目で 429</li>
 *   <li>添付: 10 回まで成功、11 回目で 429</li>
 *   <li>タグ: 20 回まで成功、21 回目で 429</li>
 *   <li>GET は対象外（透過）</li>
 *   <li>未認証 IP ベースのキーでも動作する</li>
 * </ul>
 */
@DisplayName("QuickMemoRateLimitFilter レートリミット検証")
class QuickMemoControllerTest {

    private QuickMemoRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new QuickMemoRateLimitFilter();
        // 認証済みユーザーをセット
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("200", null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ─── 認証なし 401 相当（認証済みユーザーが他人リソース操作 → 別テストで担保）──────────

    @Test
    @DisplayName("POST /api/v1/quick-memos: 60 回まで成功、61 回目で 429")
    void createMemo_rateLimit60PerMinute() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 60; i++) {
            MockHttpServletRequest request = buildRequest("/api/v1/quick-memos", "POST");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus())
                    .as("リクエスト %d 回目は200を期待", i + 1)
                    .isEqualTo(HttpServletResponse.SC_OK);
        }
        verify(chain, times(60)).doFilter(any(), any());

        // 61 回目: 429
        MockHttpServletRequest request = buildRequest("/api/v1/quick-memos", "POST");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("60");
    }

    @Test
    @DisplayName("POST /api/v1/quick-memos/*/attachments/presign: 10 回まで成功、11 回目で 429")
    void attachmentPresign_rateLimit10PerMinute() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        String path = "/api/v1/quick-memos/1/attachments/presign";

        for (int i = 0; i < 10; i++) {
            MockHttpServletRequest request = buildRequest(path, "POST");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus())
                    .as("リクエスト %d 回目は200を期待", i + 1)
                    .isEqualTo(HttpServletResponse.SC_OK);
        }
        verify(chain, times(10)).doFilter(any(), any());

        // 11 回目: 429
        MockHttpServletRequest request = buildRequest(path, "POST");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("60");
    }

    @Test
    @DisplayName("POST /api/v1/quick-memos/*/attachments/confirm: 10 回まで成功、11 回目で 429")
    void attachmentConfirm_rateLimit10PerMinute() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        String path = "/api/v1/quick-memos/1/attachments/confirm";

        for (int i = 0; i < 10; i++) {
            MockHttpServletRequest request = buildRequest(path, "POST");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus())
                    .as("リクエスト %d 回目は200を期待", i + 1)
                    .isEqualTo(HttpServletResponse.SC_OK);
        }

        // 11 回目: 429
        MockHttpServletRequest request = buildRequest(path, "POST");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(429);
    }

    @Test
    @DisplayName("POST /api/v1/me/tags: 20 回まで成功、21 回目で 429")
    void createPersonalTag_rateLimit20PerMinute() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        String path = "/api/v1/me/tags";

        for (int i = 0; i < 20; i++) {
            MockHttpServletRequest request = buildRequest(path, "POST");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus())
                    .as("リクエスト %d 回目は200を期待", i + 1)
                    .isEqualTo(HttpServletResponse.SC_OK);
        }
        verify(chain, times(20)).doFilter(any(), any());

        // 21 回目: 429
        MockHttpServletRequest request = buildRequest(path, "POST");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(429);
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
        // 認証をクリアして未認証状態にする
        SecurityContextHolder.clearContext();

        FilterChain chain = mock(FilterChain.class);
        filter = new QuickMemoRateLimitFilter();

        for (int i = 0; i < 60; i++) {
            MockHttpServletRequest request = buildRequest("/api/v1/quick-memos", "POST");
            request.setRemoteAddr("192.168.1.1");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus())
                    .as("リクエスト %d 回目は200を期待", i + 1)
                    .isEqualTo(HttpServletResponse.SC_OK);
        }

        // 61 回目: 429
        MockHttpServletRequest request = buildRequest("/api/v1/quick-memos", "POST");
        request.setRemoteAddr("192.168.1.1");
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
