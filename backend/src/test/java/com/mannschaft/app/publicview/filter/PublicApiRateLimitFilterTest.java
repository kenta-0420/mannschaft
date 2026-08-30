package com.mannschaft.app.publicview.filter;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.ratelimit.RateLimitResult;
import com.mannschaft.app.common.ratelimit.ValkeyRateLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PublicApiRateLimitFilter} のレート制限検証（Valkey 化後）。
 *
 * <p>設計書 {@code docs/features/F15.4_team_store_search_within_org.md §3.5 / §6 / §6.6}
 *      および {@code docs/features/F15.4_phase5_team_public_detail.md §4.4} に従い以下を検証する:</p>
 * <ul>
 *   <li>(検索) 未ログイン: 30 回まで成功、31 回目で 429（IP ベース）</li>
 *   <li>(検索) ログイン: 120 回まで成功、121 回目で 429（userId ベース）</li>
 *   <li>(詳細) 未ログイン: 60 回まで成功、61 回目で 429（IP ベース、Phase 5-α）</li>
 *   <li>異なる IP / 異なるユーザー間でカウントが隔離されている</li>
 *   <li>検索パスと詳細パスは別 zone（Target enum で名前空間分離）</li>
 *   <li>対象パス外（{@code GET /api/v1/organizations/{orgId}/teams}）は透過する</li>
 *   <li>非 GET メソッドは透過する</li>
 *   <li>429 レスポンスに {@code Retry-After} ヘッダー・§4.3 標準ヘッダー・JSON ボディが返る</li>
 *   <li>§6.6: レート違反時に対象に応じた AuditEventType（TEAM_SEARCH_RATE_LIMITED /
 *       PUBLIC_TEAM_DETAIL_RATE_LIMIT_EXCEEDED）が記録される</li>
 * </ul>
 *
 * <p><b>実装アプローチ（Valkey 化第一陣）</b>: {@link ValkeyRateLimiter} はモックし、
 * (zone, key) ごとの簡易カウンタで N 回目まで allowed / N+1 回目 denied を再現する。
 * 実カウント・TTL・ウィンドウ境界の検証は
 * {@code ValkeyRateLimiterIntegrationTest}（Testcontainers 実 Redis）の責務に移った。</p>
 */
@DisplayName("PublicApiRateLimitFilter レート制限検証")
class PublicApiRateLimitFilterTest {

    private static final String TARGET_PATH = "/api/v1/organizations/100/teams/search";

    private static final long RESET_EPOCH = 1_750_000_020L;
    private static final long RETRY_AFTER = 20L;

    private PublicApiRateLimitFilter filter;
    private ValkeyRateLimiter rateLimiter;
    private AuditLogService auditLogService;
    private MeterRegistry meterRegistry;
    /** (zone|key) ごとの呼び出し回数。モックがこの値と limit を比較して allowed を決める。 */
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
        ObjectProvider<ValkeyRateLimiter> rateLimiterProvider = mock(ObjectProvider.class);
        when(rateLimiterProvider.getIfAvailable()).thenReturn(rateLimiter);

        auditLogService = mock(AuditLogService.class);
        ObjectProvider<AuditLogService> auditProvider = mock(ObjectProvider.class);
        // ifAvailable(Consumer) は AuditLogService が利用可能なときに Consumer を実行する。
        // テストでは Mock を常に注入したいので、Consumer を即座に実行する。
        org.mockito.Mockito.doAnswer(invocation -> {
            Consumer<AuditLogService> consumer = invocation.getArgument(0);
            consumer.accept(auditLogService);
            return null;
        }).when(auditProvider).ifAvailable(any());

        // F19.1 Phase 5: MeterRegistry は SimpleMeterRegistry を ObjectProvider でラップして渡す
        meterRegistry = new SimpleMeterRegistry();
        ObjectProvider<MeterRegistry> meterRegistryProvider = mock(ObjectProvider.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            Consumer<MeterRegistry> consumer = invocation.getArgument(0);
            consumer.accept(meterRegistry);
            return null;
        }).when(meterRegistryProvider).ifAvailable(any());

        filter = new PublicApiRateLimitFilter(rateLimiterProvider, auditProvider, meterRegistryProvider);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        counters.clear();
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
        assertThat(response.getHeader("Retry-After")).isEqualTo(String.valueOf(RETRY_AFTER));
        assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("30");
        assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(response.getHeader("X-RateLimit-Reset")).isEqualTo(String.valueOf(RESET_EPOCH));
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString()).contains("Too many requests");
        // chain は 30 回目までしか呼ばれていない
        verify(chain, times(30)).doFilter(any(), any());

        // zone / key / limit が宣言どおり（未認証は IP キー・30/min）
        verify(rateLimiter, atLeastOnce()).tryConsume(
                eq("public-api:ORG_TEAM_SEARCH"), eq("ip:198.51.100.10"), eq(30), eq(Duration.ofMinutes(1)));
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
        assertThat(response.getHeader("Retry-After")).isEqualTo(String.valueOf(RETRY_AFTER));

        // 認証済みは u:{userId} キー・120/min
        verify(rateLimiter, atLeastOnce()).tryConsume(
                eq("public-api:ORG_TEAM_SEARCH"), eq("u:100"), eq(120), eq(Duration.ofMinutes(1)));
    }

    @Test
    @DisplayName("未ログイン: 異なる IP のカウントは隔離される")
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

        // IP-B は独立カウントを持つので 200 を返す
        MockHttpServletRequest requestB = buildRequest(TARGET_PATH, "GET");
        requestB.setRemoteAddr("198.51.100.2");
        MockHttpServletResponse responseB = new MockHttpServletResponse();
        filter.doFilter(requestB, responseB, chain);
        assertThat(responseB.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    @DisplayName("ログイン: 異なる userId のカウントは隔離される")
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

        // user=200 は独立カウントを持つので 200 を返す
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

        // shouldNotFilter() が true となり Valkey を消費せず素通り
        verify(chain, times(1)).doFilter(any(), any());
        verify(rateLimiter, never()).tryConsume(anyString(), anyString(), anyInt(), any());
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
        verify(rateLimiter, never()).tryConsume(anyString(), anyString(), anyInt(), any());
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
        assertThat(response.getHeader("Retry-After")).isEqualTo(String.valueOf(RETRY_AFTER));

        // PUBLIC_API zone・未認証 60/min
        verify(rateLimiter, atLeastOnce()).tryConsume(
                eq("public-api:PUBLIC_API"), eq("ip:198.51.100.70"), eq(60), eq(Duration.ofMinutes(1)));
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

        verify(rateLimiter, atLeastOnce()).tryConsume(
                eq("public-api:PUBLIC_API"), eq("u:555"), eq(200), eq(Duration.ofMinutes(1)));
    }

    @Test
    @DisplayName("(詳細) 検索パスの zone とは独立: 検索を 30 回消費しても詳細は影響なし")
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

        // 詳細パスは独立 zone なので 200 を返す
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
        verify(rateLimiter, never()).tryConsume(anyString(), anyString(), anyInt(), any());
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    // ────────────────────────────────────────────────────────────
    // F19.1 Phase 1: 拡張パス (organizations / posts / events)
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("(F19.1) 公開組織パス GET /public/organizations/{id} はレート対象、60 回まで成功して 61 回目で 429")
    void f19_organizationsDetail_anonymous_60PerMinute_then429() throws Exception {
        SecurityContextHolder.clearContext();
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 60; i++) {
            MockHttpServletRequest request = buildRequest("/api/v1/public/organizations/77", "GET");
            request.setRemoteAddr("198.51.100.100");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        }

        MockHttpServletRequest request = buildRequest("/api/v1/public/organizations/77", "GET");
        request.setRemoteAddr("198.51.100.100");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(429);

        // F19.1: organizations 系は PUBLIC_API_RATE_LIMIT_EXCEEDED が記録される
        ArgumentCaptor<String> metadataCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditLogService, times(1)).record(
                eq(AuditEventType.PUBLIC_API_RATE_LIMIT_EXCEEDED.name()),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                metadataCaptor.capture()
        );
        assertThat(metadataCaptor.getValue()).contains("\"organizationId\":\"77\"");
    }

    @Test
    @DisplayName("(F19.1) チーム投稿一覧 GET /public/teams/{id}/posts はレート対象")
    void f19_teamPosts_anonymous_60PerMinute_then429() throws Exception {
        SecurityContextHolder.clearContext();
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 60; i++) {
            MockHttpServletRequest request = buildRequest("/api/v1/public/teams/42/posts", "GET");
            request.setRemoteAddr("198.51.100.110");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        }

        MockHttpServletRequest request = buildRequest("/api/v1/public/teams/42/posts", "GET");
        request.setRemoteAddr("198.51.100.110");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(429);

        // F19.1: posts 系は teams 単独詳細でないため PUBLIC_API_RATE_LIMIT_EXCEEDED
        verify(auditLogService, times(1)).record(
                eq(AuditEventType.PUBLIC_API_RATE_LIMIT_EXCEEDED.name()),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any()
        );
    }

    @Test
    @DisplayName("(F19.1) チーム投稿詳細 GET /public/teams/{id}/posts/{postId} はレート対象")
    void f19_teamPostDetail_anonymous_isRateLimited() throws Exception {
        SecurityContextHolder.clearContext();
        FilterChain chain = mock(FilterChain.class);

        // 1 回叩いて 200 が返ることだけ確認（パス Pattern マッチ確認）
        MockHttpServletRequest request = buildRequest("/api/v1/public/teams/42/posts/100", "GET");
        request.setRemoteAddr("198.51.100.120");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        // Valkey 消費が行われた = shouldNotFilter で弾かれず、Filter が動作した
        verify(chain, times(1)).doFilter(any(), any());
        verify(rateLimiter, times(1)).tryConsume(
                eq("public-api:PUBLIC_API"), anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("(F19.1) チームイベント一覧 GET /public/teams/{id}/events はレート対象")
    void f19_teamEvents_anonymous_isRateLimited() throws Exception {
        SecurityContextHolder.clearContext();
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletRequest request = buildRequest("/api/v1/public/teams/42/events", "GET");
        request.setRemoteAddr("198.51.100.130");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        verify(chain, times(1)).doFilter(any(), any());
        verify(rateLimiter, times(1)).tryConsume(
                eq("public-api:PUBLIC_API"), anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("(F19.1) 組織投稿一覧 GET /public/organizations/{id}/posts はレート対象")
    void f19_orgPosts_anonymous_isRateLimited() throws Exception {
        SecurityContextHolder.clearContext();
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletRequest request = buildRequest("/api/v1/public/organizations/77/posts", "GET");
        request.setRemoteAddr("198.51.100.140");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        verify(chain, times(1)).doFilter(any(), any());
        verify(rateLimiter, times(1)).tryConsume(
                eq("public-api:PUBLIC_API"), anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("(F19.1) 公開ページ全体で同一 IP は 60 件で 429 — teams 系と organizations 系が同じ PUBLIC_API zone を共有")
    void f19_publicApi_singleBucketAcrossScopes() throws Exception {
        SecurityContextHolder.clearContext();
        FilterChain chain = mock(FilterChain.class);
        String ip = "198.51.100.150";

        // teams 詳細で 30 回消費
        for (int i = 0; i < 30; i++) {
            MockHttpServletRequest r = buildRequest("/api/v1/public/teams/42", "GET");
            r.setRemoteAddr(ip);
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(r, res, chain);
            assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        }
        // organizations 詳細で 30 回消費 → 合計 60 で上限到達
        for (int i = 0; i < 30; i++) {
            MockHttpServletRequest r = buildRequest("/api/v1/public/organizations/77", "GET");
            r.setRemoteAddr(ip);
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(r, res, chain);
            assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        }
        // 次の 1 回（teams posts）で 429
        MockHttpServletRequest over = buildRequest("/api/v1/public/teams/42/posts", "GET");
        over.setRemoteAddr(ip);
        MockHttpServletResponse overRes = new MockHttpServletResponse();
        filter.doFilter(over, overRes, chain);
        assertThat(overRes.getStatus()).isEqualTo(429);
    }

    // ────────────────────────────────────────────────────────────
    // 試練(公開活動記録): AC-19/20/21/27 — activities 系公開API のレート制限
    // 実装前の red テスト。PUBLIC_API_PATH の正規表現には activities が未登録のため
    // (backend/.../PublicApiRateLimitFilter.java:110-111)、
    // 以下のうち AC 番号付きテストは shouldNotFilter に弾かれて全て失敗する。
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("(AC-19) 公開活動記録 単票 GET /public/activities/{id} はレート対象（PUBLIC_API zone）")
    void ac19_activityDetail_anonymous_isRateLimited() throws Exception {
        SecurityContextHolder.clearContext();
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletRequest request = buildRequest("/api/v1/public/activities/900", "GET");
        request.setRemoteAddr("198.51.100.160");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        // Valkey 消費が行われた = shouldNotFilter で弾かれず、Filter が動作した
        verify(chain, times(1)).doFilter(any(), any());
        verify(rateLimiter, times(1)).tryConsume(
                eq("public-api:PUBLIC_API"), anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("(AC-20) チーム活動記録一覧 GET /public/teams/{teamId}/activities はレート対象（PUBLIC_API zone）")
    void ac20_teamActivitiesList_anonymous_isRateLimited() throws Exception {
        SecurityContextHolder.clearContext();
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletRequest request = buildRequest("/api/v1/public/teams/42/activities", "GET");
        request.setRemoteAddr("198.51.100.161");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        verify(chain, times(1)).doFilter(any(), any());
        verify(rateLimiter, times(1)).tryConsume(
                eq("public-api:PUBLIC_API"), anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("(AC-20) チーム活動記録詳細 GET /public/teams/{teamId}/activities/{id} はレート対象（PUBLIC_API zone）")
    void ac20_teamActivityDetail_anonymous_isRateLimited() throws Exception {
        SecurityContextHolder.clearContext();
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletRequest request = buildRequest("/api/v1/public/teams/42/activities/900", "GET");
        request.setRemoteAddr("198.51.100.162");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        verify(chain, times(1)).doFilter(any(), any());
        verify(rateLimiter, times(1)).tryConsume(
                eq("public-api:PUBLIC_API"), anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("(AC-20) 組織活動記録一覧 GET /public/organizations/{orgId}/activities はレート対象（PUBLIC_API zone）")
    void ac20_orgActivitiesList_anonymous_isRateLimited() throws Exception {
        SecurityContextHolder.clearContext();
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletRequest request = buildRequest("/api/v1/public/organizations/77/activities", "GET");
        request.setRemoteAddr("198.51.100.163");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        verify(chain, times(1)).doFilter(any(), any());
        verify(rateLimiter, times(1)).tryConsume(
                eq("public-api:PUBLIC_API"), anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("(AC-20) 組織活動記録詳細 GET /public/organizations/{orgId}/activities/{id} はレート対象（PUBLIC_API zone）")
    void ac20_orgActivityDetail_anonymous_isRateLimited() throws Exception {
        SecurityContextHolder.clearContext();
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletRequest request = buildRequest("/api/v1/public/organizations/77/activities/900", "GET");
        request.setRemoteAddr("198.51.100.164");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        verify(chain, times(1)).doFilter(any(), any());
        verify(rateLimiter, times(1)).tryConsume(
                eq("public-api:PUBLIC_API"), anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("(AC-27/AC-21) 公開活動記録 単票 未ログイン: 60 回まで成功、61 回目で 429"
            + "（Retry-After / X-RateLimit-* ヘッダ検証込み）")
    void ac27_activityDetail_anonymous_60PerMinute_then429() throws Exception {
        SecurityContextHolder.clearContext();
        FilterChain chain = mock(FilterChain.class);
        String path = "/api/v1/public/activities/901";
        String ip = "198.51.100.165";

        for (int i = 0; i < 60; i++) {
            MockHttpServletRequest request = buildRequest(path, "GET");
            request.setRemoteAddr(ip);
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus())
                    .as("公開活動記録 未ログイン %d 回目は 200 を期待", i + 1)
                    .isEqualTo(HttpServletResponse.SC_OK);
        }
        verify(chain, times(60)).doFilter(any(), any());

        // 61 回目: 429（AC-21: Retry-After / X-RateLimit-* ヘッダ検証）
        MockHttpServletRequest request = buildRequest(path, "GET");
        request.setRemoteAddr(ip);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo(String.valueOf(RETRY_AFTER));
        assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("60");
        assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(response.getHeader("X-RateLimit-Reset")).isEqualTo(String.valueOf(RESET_EPOCH));
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString()).contains("Too many requests");
        // chain は 60 回目までしか呼ばれていない
        verify(chain, times(60)).doFilter(any(), any());

        // zone / limit が宣言どおり（PUBLIC_API zone・未認証は IP キー・60/min）
        verify(rateLimiter, atLeastOnce()).tryConsume(
                eq("public-api:PUBLIC_API"), eq("ip:" + ip), eq(60), eq(Duration.ofMinutes(1)));
    }

    @Test
    @DisplayName("(監査) ID 直引き GET /public/activities/{id} の 429 は metadata に activityId を残す"
            + "（他の公開EPと同形式・生 IP は含めない）")
    void activityDetail_rateLimited_recordsActivityIdInAuditMetadata() throws Exception {
        SecurityContextHolder.clearContext();
        FilterChain chain = mock(FilterChain.class);
        String path = "/api/v1/public/activities/4321";
        String ip = "198.51.100.167";

        for (int i = 0; i < 60; i++) {
            MockHttpServletRequest request = buildRequest(path, "GET");
            request.setRemoteAddr(ip);
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        }
        verify(auditLogService, never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any());

        // 61 回目: 429 で記録される
        MockHttpServletRequest request = buildRequest(path, "GET");
        request.setRemoteAddr(ip);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(429);

        ArgumentCaptor<String> metadataCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditLogService, times(1)).record(
                eq(AuditEventType.PUBLIC_API_RATE_LIMIT_EXCEEDED.name()),
                isNull(), // userId（未ログイン）
                isNull(), // targetUserId
                isNull(), // teamId
                isNull(), // organizationId（スコープ非依存パスでは取れない）
                isNull(), // ipAddress（生 IP は渡さない）
                isNull(), // userAgent
                isNull(), // sessionHash
                metadataCaptor.capture()
        );

        String metadata = metadataCaptor.getValue();
        // 是正前は PUBLIC_API_PATH にマッチせず else に落ち、{"ipHash":"..."} のみで
        // 「どの ID を総当りされたか」が失われていた。
        assertThat(metadata).contains("\"activityId\":\"4321\"");
        assertThat(metadata).contains("\"ipHash\":\"");
        // 生 IP がメタデータに含まれていないこと（PII 保護）
        assertThat(metadata).doesNotContain(ip);
    }

    @Test
    @DisplayName("(監査) ログイン状態の ID 直引き 429 でも userId と activityId が両方記録される")
    void activityDetail_authenticated_rateLimited_recordsUserIdAndActivityId() throws Exception {
        setAuthenticated("8888");
        FilterChain chain = mock(FilterChain.class);
        String path = "/api/v1/public/activities/5555";

        for (int i = 0; i < 200; i++) {
            MockHttpServletRequest request = buildRequest(path, "GET");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        }
        verify(auditLogService, never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any());

        // 201 回目: 429
        MockHttpServletRequest request = buildRequest(path, "GET");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(429);

        ArgumentCaptor<String> metadataCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditLogService, times(1)).record(
                eq(AuditEventType.PUBLIC_API_RATE_LIMIT_EXCEEDED.name()),
                eq(8888L),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                metadataCaptor.capture()
        );
        assertThat(metadataCaptor.getValue()).contains("\"activityId\":\"5555\"");
    }

    @Test
    @DisplayName("(反面テスト) 公開活動記録 ルートパス GET /api/v1/public/activities（末尾IDなし）はレート対象外・透過する")
    void activitiesRootPath_isTransparent() throws Exception {
        SecurityContextHolder.clearContext();
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = buildRequest("/api/v1/public/activities", "GET");
        request.setRemoteAddr("198.51.100.166");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(any(), any());
        verify(rateLimiter, never()).tryConsume(anyString(), anyString(), anyInt(), any());
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    // ────────────────────────────────────────────────────────────
    // 公開網漏れ是正: SecurityConfig コメントと実装の食い違い是正 4 件
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("(是正) タイムライン投稿 GET /public/teams/{id}/timeline-posts はレート対象（PUBLIC_API zone）")
    void gap_teamTimelinePosts_isRateLimited() throws Exception {
        assertPublicApiRateLimited("/api/v1/public/teams/42/timeline-posts", "198.51.100.200");
    }

    @Test
    @DisplayName("(是正) 組織タイムライン投稿 GET /public/organizations/{id}/timeline-posts はレート対象（PUBLIC_API zone）")
    void gap_orgTimelinePosts_isRateLimited() throws Exception {
        assertPublicApiRateLimited("/api/v1/public/organizations/77/timeline-posts", "198.51.100.201");
    }

    @Test
    @DisplayName("(是正) 公開ユーザープロフィール GET /public/users/{id} はレート対象（PUBLIC_API zone）")
    void gap_publicUserProfile_isRateLimited() throws Exception {
        assertPublicApiRateLimited("/api/v1/public/users/900", "198.51.100.202");
    }

    @Test
    @DisplayName("(是正) 公開ユーザー投稿一覧 GET /public/users/{id}/posts はレート対象（PUBLIC_API zone）")
    void gap_publicUserPosts_isRateLimited() throws Exception {
        assertPublicApiRateLimited("/api/v1/public/users/900/posts", "198.51.100.203");
    }

    @Test
    @DisplayName("(是正) 公開投稿コメント一覧 GET /public/blog-posts/{id}/comments はレート対象（PUBLIC_API zone）")
    void gap_blogPostComments_isRateLimited() throws Exception {
        assertPublicApiRateLimited("/api/v1/public/blog-posts/321/comments", "198.51.100.204");
    }

    // ────────────────────────────────────────────────────────────
    // 公開網漏れ是正: 大会系（一覧・詳細・フォルダ = TOURNAMENT_LIST）
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("(是正) 公開大会一覧 GET /public/organizations/{id}/tournaments はレート対象（TOURNAMENT_LIST zone）")
    void gap_tournamentList_isRateLimited() throws Exception {
        assertRateLimited("/api/v1/public/organizations/77/tournaments", "198.51.100.210",
                "public-api:TOURNAMENT_LIST", 60);
    }

    @Test
    @DisplayName("(是正) 公開大会詳細 GET /public/organizations/{id}/tournaments/{id} はレート対象（TOURNAMENT_LIST zone）")
    void gap_tournamentDetail_isRateLimited() throws Exception {
        assertRateLimited("/api/v1/public/organizations/77/tournaments/500", "198.51.100.211",
                "public-api:TOURNAMENT_LIST", 60);
    }

    @Test
    @DisplayName("(是正) 大会フォルダ一覧 GET /tournaments/{id}/folders はレート対象（TOURNAMENT_LIST zone）")
    void gap_tournamentFolders_isRateLimited() throws Exception {
        assertRateLimited("/api/v1/tournaments/500/folders", "198.51.100.212",
                "public-api:TOURNAMENT_LIST", 60);
    }

    @Test
    @DisplayName("(是正) ディビジョンフォルダ一覧 GET /tournaments/{id}/divisions/{id}/folders はレート対象（TOURNAMENT_LIST zone）")
    void gap_tournamentDivisionFolders_isRateLimited() throws Exception {
        assertRateLimited("/api/v1/tournaments/500/divisions/9/folders", "198.51.100.213",
                "public-api:TOURNAMENT_LIST", 60);
    }

    // ────────────────────────────────────────────────────────────
    // 公開網漏れ是正: 大会 重い集計系（TOURNAMENT_AGGREGATE・未認証 20/min）
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("(是正) 順位表 GET .../divisions/{id}/standings は未ログイン 20 回で 429（TOURNAMENT_AGGREGATE zone）")
    void gap_tournamentStandings_20PerMinute_then429() throws Exception {
        assertAggregate20PerMinute(
                "/api/v1/public/organizations/77/tournaments/500/divisions/9/standings", "198.51.100.220");
    }

    @Test
    @DisplayName("(是正) マトリクス GET .../divisions/{id}/matrix はレート対象（TOURNAMENT_AGGREGATE zone）")
    void gap_tournamentMatrix_isRateLimited() throws Exception {
        assertRateLimited("/api/v1/public/organizations/77/tournaments/500/divisions/9/matrix",
                "198.51.100.221", "public-api:TOURNAMENT_AGGREGATE", 20);
    }

    @Test
    @DisplayName("(是正) ランキング GET .../rankings/{id} はレート対象（TOURNAMENT_AGGREGATE zone）")
    void gap_tournamentRankings_isRateLimited() throws Exception {
        assertRateLimited("/api/v1/public/organizations/77/tournaments/500/rankings/1",
                "198.51.100.222", "public-api:TOURNAMENT_AGGREGATE", 20);
    }

    @Test
    @DisplayName("(是正) 組み合わせ表 GET .../bracket はレート対象（TOURNAMENT_AGGREGATE zone）")
    void gap_tournamentBracket_isRateLimited() throws Exception {
        assertRateLimited("/api/v1/public/organizations/77/tournaments/500/bracket",
                "198.51.100.223", "public-api:TOURNAMENT_AGGREGATE", 20);
    }

    @Test
    @DisplayName("(是正) 埋め込み順位表 GET /embed/.../standings/{id} はレート対象（TOURNAMENT_AGGREGATE zone 共有）")
    void gap_embedStandings_isRateLimited() throws Exception {
        assertRateLimited("/api/v1/embed/organizations/77/tournaments/500/standings/9",
                "198.51.100.224", "public-api:TOURNAMENT_AGGREGATE", 20);
    }

    @Test
    @DisplayName("(是正) 埋め込み組み合わせ表 GET /embed/.../bracket はレート対象（TOURNAMENT_AGGREGATE zone 共有）")
    void gap_embedBracket_isRateLimited() throws Exception {
        assertRateLimited("/api/v1/embed/organizations/77/tournaments/500/bracket",
                "198.51.100.225", "public-api:TOURNAMENT_AGGREGATE", 20);
    }

    @Test
    @DisplayName("(是正) 埋め込みランキング GET /embed/.../rankings/{id} はレート対象（TOURNAMENT_AGGREGATE zone 共有）")
    void gap_embedRankings_isRateLimited() throws Exception {
        assertRateLimited("/api/v1/embed/organizations/77/tournaments/500/rankings/1",
                "198.51.100.226", "public-api:TOURNAMENT_AGGREGATE", 20);
    }

    // ────────────────────────────────────────────────────────────
    // 公開網漏れ是正: 低リスク静的・準静的系（MISC_LOW・未認証 30/min）
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("(是正) 連絡先招待プレビュー GET /contact-invite/{token} は未ログイン 30 回で 429（MISC_LOW zone）")
    void gap_contactInvite_30PerMinute_then429() throws Exception {
        assertMisc30PerMinute("/api/v1/contact-invite/abc123", "198.51.100.230");
    }

    @Test
    @DisplayName("(是正) 公開統計 GET /public/stats はレート対象（MISC_LOW zone）")
    void gap_publicStats_isRateLimited() throws Exception {
        assertRateLimited("/api/v1/public/stats", "198.51.100.231", "public-api:MISC_LOW", 30);
    }

    @Test
    @DisplayName("(是正) 郵便番号ポリシー GET /postal-code/policies はレート対象（MISC_LOW zone）")
    void gap_postalCodePolicies_isRateLimited() throws Exception {
        assertRateLimited("/api/v1/postal-code/policies", "198.51.100.232", "public-api:MISC_LOW", 30);
    }

    @Test
    @DisplayName("(是正) アクティブ障害情報 GET /active-incidents はレート対象（MISC_LOW zone）")
    void gap_activeIncidents_isRateLimited() throws Exception {
        assertRateLimited("/api/v1/active-incidents", "198.51.100.233", "public-api:MISC_LOW", 30);
    }

    // ────────────────────────────────────────────────────────────
    // 公開網漏れ是正: 署名検証済み Webhook 系（WEBHOOK・120/min・POST 限定）
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("(是正) CSP レポート受信 POST /security/csp-reports はレート対象（WEBHOOK zone）")
    void gap_cspReports_isRateLimited() throws Exception {
        assertWebhookRateLimited("/api/v1/security/csp-reports", "198.51.100.240");
    }

    @Test
    @DisplayName("(是正) Google Calendar Webhook POST はレート対象（WEBHOOK zone）")
    void gap_googleCalendarWebhook_isRateLimited() throws Exception {
        assertWebhookRateLimited("/api/v1/webhooks/google-calendar", "198.51.100.241");
    }

    @Test
    @DisplayName("(是正) SSR エラー受信 POST /internal/ssr-logs はレート対象（WEBHOOK zone）")
    void gap_ssrLogs_isRateLimited() throws Exception {
        assertWebhookRateLimited("/api/internal/ssr-logs", "198.51.100.242");
    }

    @Test
    @DisplayName("(是正) Stripe Webhook POST はレート対象（WEBHOOK zone）")
    void gap_stripeWebhook_isRateLimited() throws Exception {
        assertWebhookRateLimited("/api/v1/webhooks/stripe", "198.51.100.243");
    }

    @Test
    @DisplayName("(是正) Stripe Webhook（サブパス） POST /stripe/{eventId} はレート対象（WEBHOOK zone）")
    void gap_stripeWebhookSubpath_isRateLimited() throws Exception {
        assertWebhookRateLimited("/api/v1/webhooks/stripe/evt_123", "198.51.100.244");
    }

    @Test
    @DisplayName("(是正) LINE Webhook POST はレート対象（WEBHOOK zone）")
    void gap_lineWebhook_isRateLimited() throws Exception {
        assertWebhookRateLimited("/api/v1/line/webhook/channel1", "198.51.100.245");
    }

    @Test
    @DisplayName("(反面テスト) Webhook パスへの GET は透過する（POST のみ対象）")
    void gap_webhookPath_getMethod_isTransparent() throws Exception {
        SecurityContextHolder.clearContext();
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = buildRequest("/api/v1/security/csp-reports", "GET");
        request.setRemoteAddr("198.51.100.246");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(any(), any());
        verify(rateLimiter, never()).tryConsume(anyString(), anyString(), anyInt(), any());
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    // ────────────────────────────────────────────────────────────
    // 是正テスト用ヘルパー
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("市の検索一覧は未認証で MARKET_SEARCH の 30 req/分バケットを使う")
    void marketListingSearch_anonymous_usesSearchBucket() throws Exception {
        SecurityContextHolder.clearContext();
        String path = "/api/v1/public/market/listings";
        MockHttpServletRequest request = buildRequest(path, "GET");
        request.setRemoteAddr("198.51.100.250");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, mock(FilterChain.class));

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        verify(rateLimiter).tryConsume(eq("public-api:MARKET_SEARCH"),
                eq("ip:198.51.100.250"), eq(30), eq(Duration.ofMinutes(1)));
    }

    /** PUBLIC_API zone（60/min/IP）で 1 回叩いて Valkey 消費が行われたことだけを確認する。 */
    private void assertPublicApiRateLimited(String path, String ip) throws Exception {
        assertRateLimited(path, ip, "public-api:PUBLIC_API", 60);
    }

    /** 指定 zone・limit で 1 回叩いて Valkey 消費が行われたことだけを確認する（パスマッチ確認）。 */
    private void assertRateLimited(String path, String ip, String zone, int limit) throws Exception {
        SecurityContextHolder.clearContext();
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = buildRequest(path, "GET");
        request.setRemoteAddr(ip);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        verify(chain, times(1)).doFilter(any(), any());
        verify(rateLimiter, times(1)).tryConsume(eq(zone), anyString(), eq(limit), eq(Duration.ofMinutes(1)));
    }

    /** TOURNAMENT_AGGREGATE zone: 未ログイン 20 回まで成功、21 回目で 429。 */
    private void assertAggregate20PerMinute(String path, String ip) throws Exception {
        SecurityContextHolder.clearContext();
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 20; i++) {
            MockHttpServletRequest request = buildRequest(path, "GET");
            request.setRemoteAddr(ip);
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus())
                    .as("重い集計 未ログイン %d 回目は 200 を期待", i + 1)
                    .isEqualTo(HttpServletResponse.SC_OK);
        }

        MockHttpServletRequest request = buildRequest(path, "GET");
        request.setRemoteAddr(ip);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(429);
        verify(rateLimiter, atLeastOnce()).tryConsume(
                eq("public-api:TOURNAMENT_AGGREGATE"), eq("ip:" + ip), eq(20), eq(Duration.ofMinutes(1)));
    }

    /** MISC_LOW zone: 未ログイン 30 回まで成功、31 回目で 429。 */
    private void assertMisc30PerMinute(String path, String ip) throws Exception {
        SecurityContextHolder.clearContext();
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 30; i++) {
            MockHttpServletRequest request = buildRequest(path, "GET");
            request.setRemoteAddr(ip);
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        }

        MockHttpServletRequest request = buildRequest(path, "GET");
        request.setRemoteAddr(ip);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(429);
        verify(rateLimiter, atLeastOnce()).tryConsume(
                eq("public-api:MISC_LOW"), eq("ip:" + ip), eq(30), eq(Duration.ofMinutes(1)));
    }

    /** WEBHOOK zone: POST で 1 回叩いて Valkey 消費が行われたことだけを確認する。 */
    private void assertWebhookRateLimited(String path, String ip) throws Exception {
        SecurityContextHolder.clearContext();
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = buildRequest(path, "POST");
        request.setRemoteAddr(ip);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        verify(chain, times(1)).doFilter(any(), any());
        verify(rateLimiter, times(1)).tryConsume(
                eq("public-api:WEBHOOK"), anyString(), eq(120), eq(Duration.ofMinutes(1)));
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
