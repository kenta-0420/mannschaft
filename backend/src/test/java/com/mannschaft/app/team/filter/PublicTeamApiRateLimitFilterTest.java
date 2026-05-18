package com.mannschaft.app.team.filter;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.function.Consumer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link PublicTeamApiRateLimitFilter} のレート制限検証。
 *
 * <p>設計書 {@code docs/features/F15.4_team_store_search_within_org.md §3.5 / §6 / §6.6}
 *      および {@code docs/features/F15.4_phase5_team_public_detail.md §4.4} に従い以下を検証する:</p>
 * <ul>
 *   <li>(検索) 未ログイン: 30 回まで成功、31 回目で 429（IP ベース）</li>
 *   <li>(検索) ログイン: 120 回まで成功、121 回目で 429（userId ベース）</li>
 *   <li>(詳細) 未ログイン: 60 回まで成功、61 回目で 429（IP ベース、Phase 5-α）</li>
 *   <li>異なる IP / 異なるユーザー間でバケットが隔離されている</li>
 *   <li>検索パスと詳細パスは別バケット（Target enum で名前空間分離）</li>
 *   <li>対象パス外（{@code GET /api/v1/organizations/{orgId}/teams}）は透過する</li>
 *   <li>非 GET メソッドは透過する</li>
 *   <li>429 レスポンスに {@code Retry-After: 60} ヘッダーと JSON ボディが返る</li>
 *   <li>§6.6: レート違反時に対象に応じた AuditEventType（TEAM_SEARCH_RATE_LIMITED /
 *       PUBLIC_TEAM_DETAIL_RATE_LIMIT_EXCEEDED）が記録される</li>
 * </ul>
 *
 * <p><b>実装アプローチ</b>: 既存 {@link com.mannschaft.app.pointcard.filter.PointCardRateLimitFilter}
 * のテストと同形で、Filter を直接呼び出し Bucket4j のトークン消費を検証する。</p>
 */
@DisplayName("PublicTeamApiRateLimitFilter レート制限検証")
class PublicTeamApiRateLimitFilterTest {

    private static final String TARGET_PATH = "/api/v1/organizations/100/teams/search";

    private PublicTeamApiRateLimitFilter filter;
    private AuditLogService auditLogService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        auditLogService = mock(AuditLogService.class);
        ObjectProvider<AuditLogService> provider = mock(ObjectProvider.class);
        // ifAvailable(Consumer) は AuditLogService が利用可能なときに Consumer を実行する。
        // テストでは Mock を常に注入したいので、Consumer を即座に実行する。
        org.mockito.Mockito.doAnswer(invocation -> {
            Consumer<AuditLogService> consumer = invocation.getArgument(0);
            consumer.accept(auditLogService);
            return null;
        }).when(provider).ifAvailable(any());
        filter = new PublicTeamApiRateLimitFilter(provider);
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
    // §6.6: 監査ログ記録（AuditLogService.record 呼び出し検証）
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("§6.6: 通常リクエスト（200 応答）では AuditLogService.record は呼ばれない")
    void normalRequest_doesNotInvokeAuditLog() throws Exception {
        SecurityContextHolder.clearContext();
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 10; i++) {
            MockHttpServletRequest request = buildRequest(TARGET_PATH, "GET");
            request.setRemoteAddr("198.51.100.50");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        }

        verify(auditLogService, never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("§6.6: 未ログインで 429 発火時に TEAM_SEARCH_RATE_LIMITED が 1 回記録される（userId=null, ipHash あり）")
    void anonymous_rateLimited_recordsAuditEvent() throws Exception {
        SecurityContextHolder.clearContext();
        FilterChain chain = mock(FilterChain.class);

        // 30 回消費して上限到達
        for (int i = 0; i < 30; i++) {
            MockHttpServletRequest request = buildRequest(TARGET_PATH, "GET");
            request.setRemoteAddr("198.51.100.60");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        }
        // 通常時は呼ばれていない
        verify(auditLogService, never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any());

        // 31 回目: 429 で記録される
        MockHttpServletRequest request = buildRequest(TARGET_PATH, "GET");
        request.setRemoteAddr("198.51.100.60");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(429);

        ArgumentCaptor<String> metadataCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditLogService, times(1)).record(
                eq(AuditEventType.TEAM_SEARCH_RATE_LIMITED.name()),
                isNull(), // userId（未ログイン）
                isNull(), // targetUserId
                isNull(), // teamId
                eq(100L), // organizationId（URL から抽出）
                isNull(), // ipAddress（生 IP は渡さない）
                isNull(), // userAgent
                isNull(), // sessionHash
                metadataCaptor.capture()
        );

        String metadata = metadataCaptor.getValue();
        assertThat(metadata).contains("\"orgId\":\"100\"");
        assertThat(metadata).contains("\"ipHash\":\"");
        // 生 IP がメタデータに含まれていないこと（PII 保護）
        assertThat(metadata).doesNotContain("198.51.100.60");
    }

    @Test
    @DisplayName("§6.6: ログイン状態で 429 発火時は userId が記録される")
    void authenticated_rateLimited_recordsAuditEventWithUserId() throws Exception {
        setAuthenticated("777");
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 120; i++) {
            MockHttpServletRequest request = buildRequest(TARGET_PATH, "GET");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        }
        verify(auditLogService, never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any());

        // 121 回目: 429
        MockHttpServletRequest request = buildRequest(TARGET_PATH, "GET");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(429);

        verify(auditLogService, times(1)).record(
                eq(AuditEventType.TEAM_SEARCH_RATE_LIMITED.name()),
                eq(777L),
                isNull(),
                isNull(),
                eq(100L),
                isNull(),
                isNull(),
                isNull(),
                any()
        );
    }

    // ────────────────────────────────────────────────────────────
    // Phase 5-α: 店舗詳細 Public API (/api/v1/public/teams/{id})
    // ────────────────────────────────────────────────────────────

    private static final String DETAIL_PATH = "/api/v1/public/teams/42";

    @Test
    @DisplayName("(詳細) 未ログイン: GET /public/teams/{id} は 60 回まで成功、61 回目で 429")
    void detail_anonymous_60PerMinute_then429() throws Exception {
        SecurityContextHolder.clearContext();
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 60; i++) {
            MockHttpServletRequest request = buildRequest(DETAIL_PATH, "GET");
            request.setRemoteAddr("198.51.100.70");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus())
                    .as("詳細 未ログイン %d 回目は 200 を期待", i + 1)
                    .isEqualTo(HttpServletResponse.SC_OK);
        }

        // 61 回目: 429
        MockHttpServletRequest request = buildRequest(DETAIL_PATH, "GET");
        request.setRemoteAddr("198.51.100.70");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("60");
    }

    @Test
    @DisplayName("(詳細) ログイン: GET /public/teams/{id} は 200 回まで成功、201 回目で 429")
    void detail_authenticated_200PerMinute_then429() throws Exception {
        setAuthenticated("555");
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 200; i++) {
            MockHttpServletRequest request = buildRequest(DETAIL_PATH, "GET");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus())
                    .as("詳細 ログイン %d 回目は 200 を期待", i + 1)
                    .isEqualTo(HttpServletResponse.SC_OK);
        }

        // 201 回目: 429
        MockHttpServletRequest request = buildRequest(DETAIL_PATH, "GET");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(429);
    }

    @Test
    @DisplayName("(詳細) 検索パスのバケットとは独立: 検索を 30 回消費しても詳細は影響なし")
    void detail_separateBucketFromSearch() throws Exception {
        SecurityContextHolder.clearContext();
        FilterChain chain = mock(FilterChain.class);
        String ip = "198.51.100.80";

        // 検索パスで 30 回消費 → 上限到達
        for (int i = 0; i < 30; i++) {
            MockHttpServletRequest req = buildRequest(TARGET_PATH, "GET");
            req.setRemoteAddr(ip);
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req, res, chain);
            assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        }
        MockHttpServletRequest searchOver = buildRequest(TARGET_PATH, "GET");
        searchOver.setRemoteAddr(ip);
        MockHttpServletResponse searchOverRes = new MockHttpServletResponse();
        filter.doFilter(searchOver, searchOverRes, chain);
        assertThat(searchOverRes.getStatus()).isEqualTo(429);

        // 詳細パスは独立バケットなので 200 を返す
        MockHttpServletRequest detailReq = buildRequest(DETAIL_PATH, "GET");
        detailReq.setRemoteAddr(ip);
        MockHttpServletResponse detailRes = new MockHttpServletResponse();
        filter.doFilter(detailReq, detailRes, chain);
        assertThat(detailRes.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    @DisplayName("§6.6 (詳細): 429 発火時に PUBLIC_TEAM_DETAIL_RATE_LIMIT_EXCEEDED が記録される")
    void detail_anonymous_rateLimited_recordsAuditEvent() throws Exception {
        SecurityContextHolder.clearContext();
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 60; i++) {
            MockHttpServletRequest request = buildRequest(DETAIL_PATH, "GET");
            request.setRemoteAddr("198.51.100.90");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        }
        verify(auditLogService, never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any());

        // 61 回目: 429 で記録される
        MockHttpServletRequest request = buildRequest(DETAIL_PATH, "GET");
        request.setRemoteAddr("198.51.100.90");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(429);

        ArgumentCaptor<String> metadataCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditLogService, times(1)).record(
                eq(AuditEventType.PUBLIC_TEAM_DETAIL_RATE_LIMIT_EXCEEDED.name()),
                isNull(), // userId（未ログイン）
                isNull(),
                isNull(),
                isNull(), // organizationId は詳細パスでは取れない → null
                isNull(),
                isNull(),
                isNull(),
                metadataCaptor.capture()
        );

        String metadata = metadataCaptor.getValue();
        assertThat(metadata).contains("\"teamId\":\"42\"");
        assertThat(metadata).contains("\"ipHash\":\"");
        // 生 IP がメタデータに含まれていないこと（PII 保護）
        assertThat(metadata).doesNotContain("198.51.100.90");
    }

    @Test
    @DisplayName("対象外パス（/api/v1/public/teams ルート）は透過する")
    void detail_rootPath_isTransparent() throws Exception {
        SecurityContextHolder.clearContext();
        FilterChain chain = mock(FilterChain.class);
        // 末尾に id がない（{@code /api/v1/public/teams/}）パスは正規表現にマッチしない
        MockHttpServletRequest request = buildRequest("/api/v1/public/teams", "GET");
        request.setRemoteAddr("198.51.100.95");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
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
