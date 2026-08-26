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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RepairPlanCsvImportRateLimitFilter} のユニットテスト（Valkey 化後）。
 * ユーザー単位 5 req/分。import-csv と import-csv/confirm の両パスを対象。
 */
class RepairPlanCsvImportRateLimitFilterTest {

    private static final long RESET_EPOCH = 1_750_000_020L;
    private static final long RETRY_AFTER = 20L;

    private RepairPlanCsvImportRateLimitFilter filter;
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
        filter = new RepairPlanCsvImportRateLimitFilter(provider);
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

    private MockHttpServletRequest postImportCsv(String ip) {
        String path = "/api/v1/teams/1/repair-plan/items/import-csv";
        MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
        req.setServletPath(path);
        req.setRemoteAddr(ip);
        return req;
    }

    private MockHttpServletRequest postConfirm(String ip) {
        String path = "/api/v1/teams/1/repair-plan/items/import-csv/confirm";
        MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
        req.setServletPath(path);
        req.setRemoteAddr(ip);
        return req;
    }

    @Nested
    @DisplayName("POST import-csv — 5 req/分・ユーザーキー")
    class ImportCsvLimit {

        @Test
        @DisplayName("同一ユーザーが 5 回通過、6 回目で 429 / Retry-After / X-RateLimit-* / JSON ボディ")
        void exceedsLimitReturns429() throws Exception {
            authenticateAs("user-alice");
            String ip = "10.0.0.1";

            for (int i = 0; i < 5; i++) {
                assertThat(invoke(postImportCsv(ip)).getStatus())
                        .as("import-csv POST #%d should pass", i + 1)
                        .isEqualTo(HttpStatus.OK.value());
            }

            MockHttpServletResponse overLimit = invoke(postImportCsv(ip));
            assertThat(overLimit.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
            assertThat(overLimit.getHeader("Retry-After")).isEqualTo(String.valueOf(RETRY_AFTER));
            assertThat(overLimit.getHeader("X-RateLimit-Limit")).isEqualTo("5");
            assertThat(overLimit.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
            assertThat(overLimit.getHeader("X-RateLimit-Reset")).isEqualTo(String.valueOf(RESET_EPOCH));
            assertThat(overLimit.getContentAsString()).contains("Too many requests");

            verify(rateLimiter, atLeastOnce()).tryConsume(
                    eq("repairplan:csv-import"), eq("u:user-alice"), eq(5), eq(Duration.ofMinutes(1)));
        }

        @Test
        @DisplayName("confirm も同じ zone でカウントされる（5 req/分共有）")
        void confirmUsesSharedZone() throws Exception {
            authenticateAs("user-alice");
            String ip = "10.0.0.2";

            for (int i = 0; i < 5; i++) {
                assertThat(invoke(postConfirm(ip)).getStatus())
                        .as("import-csv/confirm POST #%d should pass", i + 1)
                        .isEqualTo(HttpStatus.OK.value());
            }
            assertThat(invoke(postConfirm(ip)).getStatus())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());

            verify(rateLimiter, atLeastOnce()).tryConsume(
                    eq("repairplan:csv-import"), eq("u:user-alice"), eq(5), any());
        }
    }

    @Nested
    @DisplayName("対象外エンドポイントはスキップされる")
    class SkippedEndpoints {

        @Test
        @DisplayName("GET import-csv はフィルタ対象外")
        void getIsNotFiltered() {
            MockHttpServletRequest req = new MockHttpServletRequest("GET",
                    "/api/v1/teams/1/repair-plan/items/import-csv");
            req.setServletPath("/api/v1/teams/1/repair-plan/items/import-csv");
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
            ObjectProvider<ValkeyRateLimiter> emptyProvider = mock(ObjectProvider.class);
            when(emptyProvider.getIfAvailable()).thenReturn(null);
            RepairPlanCsvImportRateLimitFilter beanlessFilter =
                    new RepairPlanCsvImportRateLimitFilter(emptyProvider);

            MockHttpServletResponse response = new MockHttpServletResponse();
            beanlessFilter.doFilter(postImportCsv("10.0.7.1"), response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        }
    }
}
