package com.mannschaft.app.repairplan.filter;

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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RepairPlanSimulateRateLimitFilter} のユニットテスト（Valkey 化後）。
 *
 * <p>二重制限（ユーザー 20/分 + スコープ 100/分）を検証する。
 * 両方 allowed で通過、どちらかが超過で 429。</p>
 */
class RepairPlanSimulateRateLimitFilterTest {

    private static final long RESET_EPOCH = 1_750_000_020L;
    private static final long RETRY_AFTER = 20L;

    private RepairPlanSimulateRateLimitFilter filter;
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
        filter = new RepairPlanSimulateRateLimitFilter(provider);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        counters.clear();
    }

    private void authenticateAs(String userId) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                userId, "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private MockHttpServletResponse invoke(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return response;
    }

    private MockHttpServletRequest postSimulate(String scopeType, String scopeId, String ip) {
        String path = "/api/v1/" + scopeType + "/" + scopeId + "/repair-plan/scenarios/simulate";
        MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
        req.setServletPath(path);
        req.setRemoteAddr(ip);
        return req;
    }

    @Nested
    @DisplayName("ユーザー制限: 20 req/分")
    class UserLimit {

        @Test
        @DisplayName("同一ユーザーが 20 回通過、21 回目で 429 / Retry-After / X-RateLimit-* / JSON ボディ")
        void exceedsUserLimitReturns429() throws Exception {
            authenticateAs("user-alice");
            String ip = "10.0.0.1";

            for (int i = 0; i < 20; i++) {
                assertThat(invoke(postSimulate("teams", "1", ip)).getStatus())
                        .as("simulate POST #%d should pass", i + 1)
                        .isEqualTo(HttpStatus.OK.value());
            }

            MockHttpServletResponse overLimit = invoke(postSimulate("teams", "1", ip));
            assertThat(overLimit.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
            assertThat(overLimit.getHeader("Retry-After")).isEqualTo(String.valueOf(RETRY_AFTER));
            assertThat(overLimit.getHeader("X-RateLimit-Limit")).isNotNull();
            assertThat(overLimit.getContentAsString()).contains("Too many requests");

            // ユーザー zone で 21 回 tryConsume されている
            verify(rateLimiter, atLeastOnce()).tryConsume(
                    eq("repairplan:simulate:user"), eq("u:user-alice"), eq(20), eq(Duration.ofMinutes(1)));
        }
    }

    @Nested
    @DisplayName("スコープ制限: 100 req/分")
    class ScopeLimit {

        @Test
        @DisplayName("スコープ制限（100/分）で 429 になる — scope:teams:1 キーで制限")
        void exceedsScopeLimitReturns429() throws Exception {
            // スコープカウンタを上限到達済みにする（ユーザー制限 20/分 は user-new の初回なので通過する）
            counters.computeIfAbsent("repairplan:simulate:scope|scope:teams:1", k -> new AtomicLong())
                    .set(100);
            authenticateAs("user-new");
            MockHttpServletResponse overLimit = invoke(postSimulate("teams", "1", "10.0.0.1"));
            assertThat(overLimit.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());

            verify(rateLimiter, atLeastOnce()).tryConsume(
                    eq("repairplan:simulate:scope"), eq("scope:teams:1"), eq(100), eq(Duration.ofMinutes(1)));
        }

        @Test
        @DisplayName("スコープキーは scope:{scopeType}:{scopeId} 形式")
        void scopeKeyFormat() throws Exception {
            authenticateAs("user-alice");
            invoke(postSimulate("organizations", "42", "10.0.0.1"));

            verify(rateLimiter, atLeastOnce()).tryConsume(
                    eq("repairplan:simulate:scope"), eq("scope:organizations:42"), eq(100), any());
        }
    }

    @Nested
    @DisplayName("両方 allowed で通過、どちらかが超過で 429")
    class DualRuleLogic {

        @Test
        @DisplayName("ユーザー: allowed / スコープ: allowed → 通過（200）")
        void bothAllowed_passes() throws Exception {
            authenticateAs("user-alice");
            assertThat(invoke(postSimulate("teams", "1", "10.0.0.1")).getStatus())
                    .isEqualTo(HttpStatus.OK.value());
        }

        @Test
        @DisplayName("ユーザー超過時はスコープ側を消費しない（短絡評価 — 同一スコープ他ユーザーの巻き添え防止）")
        void userExceeded_doesNotConsumeScope() throws Exception {
            authenticateAs("user-alice");
            // ユーザーカウンタを上限到達済みにする
            counters.computeIfAbsent("repairplan:simulate:user|u:user-alice", k -> new AtomicLong())
                    .set(20);

            MockHttpServletResponse overLimit = invoke(postSimulate("teams", "1", "10.0.0.1"));
            assertThat(overLimit.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
            // user 側（limit=20）のヘッダーが返る
            assertThat(overLimit.getHeader("X-RateLimit-Limit")).isEqualTo("20");
            // scope zone は一切消費されない（旧 Bucket4j 実装の短絡意味論を維持）
            verify(rateLimiter, never()).tryConsume(
                    eq("repairplan:simulate:scope"), anyString(), anyInt(), any());
        }
    }

    @Nested
    @DisplayName("対象外エンドポイントはスキップされる")
    class SkippedEndpoints {

        @Test
        @DisplayName("GET simulate はフィルタ対象外")
        void getIsNotFiltered() {
            MockHttpServletRequest req = new MockHttpServletRequest("GET",
                    "/api/v1/teams/1/repair-plan/scenarios/simulate");
            req.setServletPath("/api/v1/teams/1/repair-plan/scenarios/simulate");
            assertThat(filter.shouldNotFilter(req)).isTrue();
        }
    }

    @Nested
    @DisplayName("ValkeyRateLimiter Bean 不在（最小テストコンテキスト互換）")
    class LimiterBeanAbsent {

        @Test
        @DisplayName("ValkeyRateLimiter が解決できない場合は素通しする")
        @SuppressWarnings("unchecked")
        void passesThroughWhenLimiterUnavailable() throws Exception {
            authenticateAs("user-alice");
            ObjectProvider<ValkeyRateLimiter> emptyProvider = mock(ObjectProvider.class);
            when(emptyProvider.getIfAvailable()).thenReturn(null);
            RepairPlanSimulateRateLimitFilter beanlessFilter =
                    new RepairPlanSimulateRateLimitFilter(emptyProvider);

            MockHttpServletResponse response = new MockHttpServletResponse();
            beanlessFilter.doFilter(postSimulate("teams", "1", "10.0.7.1"),
                    response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        }
    }
}
