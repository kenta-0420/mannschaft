package com.mannschaft.app.village;

import com.mannschaft.app.common.ratelimit.RateLimitResult;
import com.mannschaft.app.common.ratelimit.ValkeyRateLimiter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F17.2 ⑤相性表示 AC-21b — {@link VillageAffinityRateLimitFilter} のユニットテスト。
 *
 * <p>金型: {@code DashboardScopeTabRateLimitFilterTest}（{@link MockFilterChain} + モック
 * {@link ValkeyRateLimiter} を in-memory カウントに差し替え・{@code setServletPath} で対象判定）。
 * 実 Valkey / Docker 不要で決定論的に 429 境界を検証できる。</p>
 *
 * <p>本フィルタの要点は<strong>制限主体キーに villageId を含める</strong>こと（§8.4 緩和2）。
 * 「所属村集合を1村ずつ変えながら同一村を叩く」差分攻撃を<strong>村単位</strong>で捕捉するため、
 * userId 単独でなく userId+villageId でカウントする。</p>
 */
class VillageAffinityRateLimitFilterTest {

    private static final long RESET_EPOCH = 1_750_000_030L;
    private static final long RETRY_AFTER = 30L;
    private static final int LIMIT = 30;

    private static final String VILLAGE_A = "0192a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b";
    private static final String VILLAGE_B = "0192ffff-c3d4-7e5f-8a9b-0c1d2e3f4a5b";

    private VillageAffinityRateLimitFilter filter;
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
        filter = new VillageAffinityRateLimitFilter(provider);
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

    private MockHttpServletRequest affinityGet(String villageId, String ip) {
        String path = "/api/v1/villages/" + villageId + "/affinity/me";
        MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
        req.setServletPath(path);
        req.setRemoteAddr(ip);
        return req;
    }

    private void authenticateAs(String userId) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                userId, "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @Nested
    @DisplayName("GET /api/v1/villages/{id}/affinity/me — 30 req/分（userId+villageId キー）")
    class AffinityLimit {

        @Test
        @DisplayName("AC-21b: 同一ユーザー×同一村で 30 回までは通過、31 回目で 429（キー=u:{userId}:v:{villageId}）")
        void exceedsLimitReturns429() throws Exception {
            authenticateAs("42");

            for (int i = 0; i < LIMIT; i++) {
                assertThat(invoke(affinityGet(VILLAGE_A, "10.0.0.1")).getStatus())
                        .as("affinity GET #%d should pass", i + 1)
                        .isEqualTo(HttpStatus.OK.value());
            }

            MockHttpServletResponse overLimit = invoke(affinityGet(VILLAGE_A, "10.0.0.1"));
            assertThat(overLimit.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
            assertThat(overLimit.getHeader("Retry-After")).isEqualTo(String.valueOf(RETRY_AFTER));
            assertThat(overLimit.getHeader("X-RateLimit-Limit")).isEqualTo("30");
            assertThat(overLimit.getContentAsString()).contains("Too many requests");

            // 制限主体キーに villageId が含まれる（村単位カウント・§8.4 緩和2）
            verify(rateLimiter, atLeastOnce()).tryConsume(
                    eq("village:affinity"), eq("u:42:v:" + VILLAGE_A), eq(30), eq(Duration.ofMinutes(1)));
        }

        @Test
        @DisplayName("AC-21b: レート制限は村単位（同一ユーザーでも別村は独立カウントで 429 に巻き込まれない）")
        void perVillageCounting() throws Exception {
            authenticateAs("42");

            // villageA を上限まで使い切って 429 に到達させる
            for (int i = 0; i < LIMIT; i++) {
                assertThat(invoke(affinityGet(VILLAGE_A, "10.0.0.2")).getStatus())
                        .isEqualTo(HttpStatus.OK.value());
            }
            assertThat(invoke(affinityGet(VILLAGE_A, "10.0.0.2")).getStatus())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());

            // 別村 villageB は独立キーなので同一ユーザーでも 200
            assertThat(invoke(affinityGet(VILLAGE_B, "10.0.0.2")).getStatus())
                    .isEqualTo(HttpStatus.OK.value());
            verify(rateLimiter, atLeastOnce()).tryConsume(
                    eq("village:affinity"), eq("u:42:v:" + VILLAGE_B), eq(30), any());
        }

        @Test
        @DisplayName("§4.3: 通過時にも X-RateLimit-* ヘッダーが付与される")
        void standardHeadersOnSuccess() throws Exception {
            authenticateAs("7");
            MockHttpServletResponse response = invoke(affinityGet(VILLAGE_A, "10.0.0.3"));
            assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
            assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("30");
            assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("29");
            assertThat(response.getHeader("Retry-After")).isNull();
        }
    }

    @Nested
    @DisplayName("対象外リクエストはスキップされる")
    class Skipped {

        @Test
        @DisplayName("POST（メソッド違い）はフィルタ対象外")
        void postIsNotFiltered() {
            String path = "/api/v1/villages/" + VILLAGE_A + "/affinity/me";
            MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
            req.setServletPath(path);
            assertThat(filter.shouldNotFilter(req)).isTrue();
        }

        @Test
        @DisplayName("別パス（serendipity-scores）はフィルタ対象外")
        void otherPathIsNotFiltered() {
            String path = "/api/v1/villages/" + VILLAGE_A + "/serendipity-scores/me";
            MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
            req.setServletPath(path);
            assertThat(filter.shouldNotFilter(req)).isTrue();
        }
    }
}
