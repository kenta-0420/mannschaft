package com.mannschaft.app.team.filter;

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
 * {@link OrganizationTeamSearchRateLimitFilter} のレート制限検証。
 *
 * <p>設計書 {@code docs/features/F15.4_team_store_search_within_org.md §3.5 / §6} に従い以下を検証する:</p>
 * <ul>
 *   <li>未ログイン: 30 回まで成功、31 回目で 429（IP ベース）</li>
 *   <li>ログイン: 120 回まで成功、121 回目で 429（userId ベース）</li>
 *   <li>異なる IP / 異なるユーザー間でバケットが隔離されている</li>
 *   <li>対象パス外（{@code GET /api/v1/organizations/{orgId}/teams}）は透過する</li>
 *   <li>非 GET メソッドは透過する</li>
 *   <li>429 レスポンスに {@code Retry-After: 60} ヘッダーと JSON ボディが返る</li>
 * </ul>
 *
 * <p><b>実装アプローチ</b>: 既存 {@link com.mannschaft.app.pointcard.filter.PointCardRateLimitFilter}
 * のテストと同形で、Filter を直接呼び出し Bucket4j のトークン消費を検証する。</p>
 */
@DisplayName("OrganizationTeamSearchRateLimitFilter レート制限検証")
class OrganizationTeamSearchRateLimitFilterTest {

    private static final String TARGET_PATH = "/api/v1/organizations/100/teams/search";

    private OrganizationTeamSearchRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new OrganizationTeamSearchRateLimitFilter();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("未ログイン: GET /search は 30 回まで成功、31 回目で 429")
    void anonymous_30PerMinute_then429() throws Exception {
        SecurityContextHolder.clearContext();
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 30; i++) {
            MockHttpServletRequest request = buildRequest(TARGET_PATH, "GET");
            request.setRemoteAddr("198.51.100.10");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus())
                    .as("未ログイン %d 回目は 200 を期待", i + 1)
                    .isEqualTo(HttpServletResponse.SC_OK);
        }
        verify(chain, times(30)).doFilter(any(), any());

        // 31 回目: 429
        MockHttpServletRequest request = buildRequest(TARGET_PATH, "GET");
        request.setRemoteAddr("198.51.100.10");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("60");
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString()).contains("Too many requests");
        // chain は 30 回目までしか呼ばれていない
        verify(chain, times(30)).doFilter(any(), any());
    }

    @Test
    @DisplayName("ログイン: GET /search は 120 回まで成功、121 回目で 429")
    void authenticated_120PerMinute_then429() throws Exception {
        setAuthenticated("100");
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 120; i++) {
            MockHttpServletRequest request = buildRequest(TARGET_PATH, "GET");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus())
                    .as("ログイン %d 回目は 200 を期待", i + 1)
                    .isEqualTo(HttpServletResponse.SC_OK);
        }
        verify(chain, times(120)).doFilter(any(), any());

        // 121 回目: 429
        MockHttpServletRequest request = buildRequest(TARGET_PATH, "GET");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("60");
    }

    @Test
    @DisplayName("未ログイン: 異なる IP のバケットは隔離される")
    void anonymous_separateIp_separateBuckets() throws Exception {
        SecurityContextHolder.clearContext();
        FilterChain chain = mock(FilterChain.class);

        // IP-A で 30 回消費 → 上限到達
        for (int i = 0; i < 30; i++) {
            MockHttpServletRequest request = buildRequest(TARGET_PATH, "GET");
            request.setRemoteAddr("198.51.100.1");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        }
        // IP-A の 31 回目は 429
        MockHttpServletRequest requestA = buildRequest(TARGET_PATH, "GET");
        requestA.setRemoteAddr("198.51.100.1");
        MockHttpServletResponse responseA = new MockHttpServletResponse();
        filter.doFilter(requestA, responseA, chain);
        assertThat(responseA.getStatus()).isEqualTo(429);

        // IP-B は独立バケットを持つので 200 を返す
        MockHttpServletRequest requestB = buildRequest(TARGET_PATH, "GET");
        requestB.setRemoteAddr("198.51.100.2");
        MockHttpServletResponse responseB = new MockHttpServletResponse();
        filter.doFilter(requestB, responseB, chain);
        assertThat(responseB.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    @DisplayName("ログイン: 異なる userId のバケットは隔離される")
    void authenticated_separateUser_separateBuckets() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        // user=100 で 120 回消費 → 上限到達
        setAuthenticated("100");
        for (int i = 0; i < 120; i++) {
            MockHttpServletRequest request = buildRequest(TARGET_PATH, "GET");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        }
        // user=100 の 121 回目は 429
        MockHttpServletRequest req100 = buildRequest(TARGET_PATH, "GET");
        MockHttpServletResponse res100 = new MockHttpServletResponse();
        filter.doFilter(req100, res100, chain);
        assertThat(res100.getStatus()).isEqualTo(429);

        // user=200 は独立バケットを持つので 200 を返す
        setAuthenticated("200");
        MockHttpServletRequest req200 = buildRequest(TARGET_PATH, "GET");
        MockHttpServletResponse res200 = new MockHttpServletResponse();
        filter.doFilter(req200, res200, chain);
        assertThat(res200.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    @DisplayName("対象外パス（/teams のみ・末尾に /search が無い）は透過する")
    void otherPath_isTransparent() throws Exception {
        SecurityContextHolder.clearContext();
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = buildRequest("/api/v1/organizations/100/teams", "GET");
        request.setRemoteAddr("198.51.100.20");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        // shouldNotFilter() が true となり Bucket4j を消費せず素通り
        verify(chain, times(1)).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    @DisplayName("POST メソッドは透過する（GET のみが対象）")
    void postMethod_isTransparent() throws Exception {
        SecurityContextHolder.clearContext();
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = buildRequest(TARGET_PATH, "POST");
        request.setRemoteAddr("198.51.100.30");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    @DisplayName("別組織 ID の /search パスでも 30 件制限が個別に適用される（同一 IP では合算）")
    void anonymous_differentOrgIdSameIp_sharesBucket() throws Exception {
        SecurityContextHolder.clearContext();
        FilterChain chain = mock(FilterChain.class);

        // 同一 IP で組織 ID 100 と 200 へアクセス → IP キーで合算され 30 回到達で 429
        for (int i = 0; i < 15; i++) {
            MockHttpServletRequest request = buildRequest("/api/v1/organizations/100/teams/search", "GET");
            request.setRemoteAddr("198.51.100.40");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        }
        for (int i = 0; i < 15; i++) {
            MockHttpServletRequest request = buildRequest("/api/v1/organizations/200/teams/search", "GET");
            request.setRemoteAddr("198.51.100.40");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        }
        // 31 回目（同一 IP）は 429
        MockHttpServletRequest request = buildRequest("/api/v1/organizations/300/teams/search", "GET");
        request.setRemoteAddr("198.51.100.40");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(429);
    }

    // ────────────────────────────────────────────────────────────
    // ヘルパー
    // ────────────────────────────────────────────────────────────

    private void setAuthenticated(String userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private MockHttpServletRequest buildRequest(String path, String method) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServletPath(path);
        request.setMethod(method);
        request.setRemoteAddr("127.0.0.1");
        return request;
    }
}
