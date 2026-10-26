package com.mannschaft.app.actionmemo;

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
 * {@link ActionMemoRateLimitFilter} のユニットテスト（Valkey 化後）。
 *
 * <p>{@link ValkeyRateLimiter} はモックし、フィルタの責務である
 * 「エンドポイント判定 / (zone, limit, window) 宣言 / キー解決 / 429 応答・§4.3 ヘッダー」を検証する。
 * モックは (zone, key) ごとの簡易カウンタで N 回目まで allowed / N+1 回目 denied を再現する。</p>
 *
 * <p><b>注</b>: 実カウント・TTL・ウィンドウ境界の検証は
 * {@code ValkeyRateLimiterIntegrationTest}（Testcontainers 実 Redis）の責務に移った。</p>
 */
class ActionMemoRateLimitFilterTest {

    private static final long RESET_EPOCH = 1_750_000_020L;
    private static final long RETRY_AFTER = 20L;

    private ActionMemoRateLimitFilter filter;
    private ValkeyRateLimiter rateLimiter;
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

        ObjectProvider<ValkeyRateLimiter> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(rateLimiter);
        filter = new ActionMemoRateLimitFilter(provider);
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

    private MockHttpServletRequest request(String method, String path, String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        request.setRemoteAddr(ip);
        return request;
    }

    private void authenticateAs(String userId) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                userId, "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Nested
    @DisplayName("POST /api/v1/action-memos — 60 req/分")
    class CreateMemoLimit {

        @Test
        @DisplayName("同一 IP から 60 回までは通過、61 回目で 429 / Retry-After / X-RateLimit-* / JSON ボディ")
        void exceedsLimitReturns429() throws Exception {
            String ip = "10.0.0.1";

            for (int i = 0; i < 60; i++) {
                MockHttpServletResponse response = invoke(request("POST", "/api/v1/action-memos", ip));
                assertThat(response.getStatus())
                        .as("action-memos POST #%d should pass", i + 1)
                        .isEqualTo(HttpStatus.OK.value());
            }

            MockHttpServletResponse overLimit = invoke(request("POST", "/api/v1/action-memos", ip));
            assertThat(overLimit.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
            assertThat(overLimit.getHeader("Retry-After")).isEqualTo(String.valueOf(RETRY_AFTER));
            assertThat(overLimit.getHeader("X-RateLimit-Limit")).isEqualTo("60");
            assertThat(overLimit.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
            assertThat(overLimit.getHeader("X-RateLimit-Reset")).isEqualTo(String.valueOf(RESET_EPOCH));
            assertThat(overLimit.getContentAsString()).contains("Too many requests");

            // zone / limit / window が宣言どおりに渡っている
            verify(rateLimiter, atLeastOnce()).tryConsume(
                    eq("action-memo:CREATE_MEMO"), eq("ip:" + ip), eq(60), eq(Duration.ofMinutes(1)));
        }

        @Test
        @DisplayName("§4.3: 通過時にも X-RateLimit-* ヘッダーが付与される")
        void standardHeadersOnSuccess() throws Exception {
            MockHttpServletResponse response = invoke(request("POST", "/api/v1/action-memos", "10.0.0.8"));

            assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
            assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("60");
            assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("59");
            assertThat(response.getHeader("X-RateLimit-Reset")).isEqualTo(String.valueOf(RESET_EPOCH));
            assertThat(response.getHeader("Retry-After")).isNull();
        }

        @Test
        @DisplayName("異なる IP はキーが分かれカウントが独立する")
        void isolatedByIp() throws Exception {
            String ipA = "10.0.0.2";
            String ipB = "10.0.0.3";

            for (int i = 0; i < 60; i++) {
                assertThat(invoke(request("POST", "/api/v1/action-memos", ipA)).getStatus())
                        .isEqualTo(HttpStatus.OK.value());
            }
            assertThat(invoke(request("POST", "/api/v1/action-memos", ipA)).getStatus())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());

            // ipB は独立（key が "ip:10.0.0.3" で別カウント）
            assertThat(invoke(request("POST", "/api/v1/action-memos", ipB)).getStatus())
                    .isEqualTo(HttpStatus.OK.value());
        }

        @Test
        @DisplayName("認証済みユーザーは u:{userId} キーでカウントされる")
        void authenticatedUserKeyedByUserId() throws Exception {
            authenticateAs("user-alice");

            for (int i = 0; i < 60; i++) {
                assertThat(invoke(request("POST", "/api/v1/action-memos", "10.0.0.9")).getStatus())
                        .isEqualTo(HttpStatus.OK.value());
            }
            assertThat(invoke(request("POST", "/api/v1/action-memos", "10.0.0.9")).getStatus())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
            verify(rateLimiter, atLeastOnce()).tryConsume(
                    eq("action-memo:CREATE_MEMO"), eq("u:user-alice"), eq(60), any());

            // 同じ IP でも別ユーザーなら通る
            authenticateAs("user-bob");
            assertThat(invoke(request("POST", "/api/v1/action-memos", "10.0.0.9")).getStatus())
                    .isEqualTo(HttpStatus.OK.value());
        }

        @Test
        @DisplayName("§4.4: X-Forwarded-For がある場合は先頭値を IP キーに使う")
        void xForwardedForTakesPrecedence() throws Exception {
            MockHttpServletRequest req = request("POST", "/api/v1/action-memos", "10.0.0.99");
            req.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.99");

            assertThat(invoke(req).getStatus()).isEqualTo(HttpStatus.OK.value());

            verify(rateLimiter).tryConsume(
                    eq("action-memo:CREATE_MEMO"), eq("ip:203.0.113.7"), eq(60), any());
        }
    }

    @Nested
    @DisplayName("POST /api/v1/action-memos/publish-daily — 5 req/分")
    class PublishDailyLimit {

        @Test
        @DisplayName("同一 IP から 5 回までは通過、6 回目で 429")
        void exceedsLimit() throws Exception {
            String ip = "10.0.1.1";

            for (int i = 0; i < 5; i++) {
                assertThat(invoke(request("POST", "/api/v1/action-memos/publish-daily", ip)).getStatus())
                        .isEqualTo(HttpStatus.OK.value());
            }
            assertThat(invoke(request("POST", "/api/v1/action-memos/publish-daily", ip)).getStatus())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
            verify(rateLimiter, atLeastOnce()).tryConsume(
                    eq("action-memo:PUBLISH_DAILY"), eq("ip:" + ip), eq(5), eq(Duration.ofMinutes(1)));
        }
    }

    @Nested
    @DisplayName("POST /api/v1/action-memo-tags — 20 req/分")
    class CreateTagLimit {

        @Test
        @DisplayName("同一 IP から 20 回までは通過、21 回目で 429")
        void exceedsLimit() throws Exception {
            String ip = "10.0.2.1";

            for (int i = 0; i < 20; i++) {
                assertThat(invoke(request("POST", "/api/v1/action-memo-tags", ip)).getStatus())
                        .isEqualTo(HttpStatus.OK.value());
            }
            assertThat(invoke(request("POST", "/api/v1/action-memo-tags", ip)).getStatus())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
            verify(rateLimiter, atLeastOnce()).tryConsume(
                    eq("action-memo:CREATE_TAG"), eq("ip:" + ip), eq(20), eq(Duration.ofMinutes(1)));
        }
    }

    @Nested
    @DisplayName("PATCH /api/v1/action-memo-settings — 10 req/分")
    class UpdateSettingsLimit {

        @Test
        @DisplayName("同一 IP から 10 回までは通過、11 回目で 429")
        void exceedsLimit() throws Exception {
            String ip = "10.0.3.1";

            for (int i = 0; i < 10; i++) {
                assertThat(invoke(request("PATCH", "/api/v1/action-memo-settings", ip)).getStatus())
                        .isEqualTo(HttpStatus.OK.value());
            }
            assertThat(invoke(request("PATCH", "/api/v1/action-memo-settings", ip)).getStatus())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
            verify(rateLimiter, atLeastOnce()).tryConsume(
                    eq("action-memo:UPDATE_SETTINGS"), eq("ip:" + ip), eq(10), eq(Duration.ofMinutes(1)));
        }
    }

    @Nested
    @DisplayName("エンドポイント間の zone 分離")
    class EndpointIsolation {

        @Test
        @DisplayName("create-memo を使い切っても publish-daily / create-tag / settings は独立して通る")
        void endpointsAreIndependent() throws Exception {
            String ip = "10.0.4.1";

            // create-memo を上限まで消費
            for (int i = 0; i < 60; i++) {
                assertThat(invoke(request("POST", "/api/v1/action-memos", ip)).getStatus())
                        .isEqualTo(HttpStatus.OK.value());
            }
            assertThat(invoke(request("POST", "/api/v1/action-memos", ip)).getStatus())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());

            // 他エンドポイントは zone が異なるため独立して通過する
            assertThat(invoke(request("POST", "/api/v1/action-memos/publish-daily", ip)).getStatus())
                    .isEqualTo(HttpStatus.OK.value());
            assertThat(invoke(request("POST", "/api/v1/action-memo-tags", ip)).getStatus())
                    .isEqualTo(HttpStatus.OK.value());
            assertThat(invoke(request("PATCH", "/api/v1/action-memo-settings", ip)).getStatus())
                    .isEqualTo(HttpStatus.OK.value());
        }
    }

    @Nested
    @DisplayName("Phase 3 パス: /publish-to-team / /publish-daily-to-team")
    class Phase3PublishToTeamPaths {

        /**
         * <p><b>Spec drift</b>: 設計書 §9.2 では「{@code publish-to-team}: 10回/分」と定義されているが、
         * {@link ActionMemoRateLimitFilter} の {@code Endpoint} enum には
         * {@code /publish-to-team} / {@code /publish-daily-to-team} が含まれておらず、
         * これらのパスは現状フィルタ対象外（無制限）である。
         * 本テストは現実装の挙動（filter 透過）を回帰防止しつつ、Spec drift の存在を明示する。
         * 実装で path が追加された際にはこのテストを「閾値超過で 429」を assert する形に書き換えること。</p>
         */
        @Test
        @DisplayName("POST /publish-to-team: 現状 filter 対象外（shouldNotFilter=true）— Spec drift 注記")
        void publishToTeam_currentlyNotFiltered() {
            MockHttpServletRequest request = request("POST", "/api/v1/action-memos/1/publish-to-team", "10.0.6.1");
            assertThat(filter.shouldNotFilter(request))
                    .as("publish-to-team は現実装で filter 対象外（設計書 §9.2 とは drift）")
                    .isTrue();
        }

        @Test
        @DisplayName("POST /publish-to-team: 12 回連続実行しても 429 にはならない（filter 透過・Valkey 消費なし）")
        void publishToTeam_exceedingDesignLimit_currentlyNoLimit() throws Exception {
            String ip = "10.0.6.2";
            // 設計書の閾値（10回/分）を超えても、現実装は filter 透過のため全件 200 OK が期待される
            for (int i = 0; i < 12; i++) {
                MockHttpServletResponse response = invoke(
                        request("POST", "/api/v1/action-memos/100/publish-to-team", ip));
                assertThat(response.getStatus())
                        .as("filter 対象外の path は透過する想定 (#%d)", i + 1)
                        .isEqualTo(HttpStatus.OK.value());
            }
            verify(rateLimiter, never()).tryConsume(anyString(), anyString(), anyInt(), any());
        }

        @Test
        @DisplayName("POST /publish-daily-to-team: 現状 filter 対象外（shouldNotFilter=true）")
        void publishDailyToTeam_currentlyNotFiltered() {
            MockHttpServletRequest request = request(
                    "POST", "/api/v1/action-memos/publish-daily-to-team", "10.0.6.3");
            assertThat(filter.shouldNotFilter(request))
                    .as("publish-daily-to-team も Phase 3 で追加されたが、filter 対象外のまま")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("ValkeyRateLimiter Bean 不在（最小テストコンテキスト互換）")
    class LimiterBeanAbsent {

        @Test
        @DisplayName("ValkeyRateLimiter が解決できない場合は素通しする（@WebMvcTest スライス互換）")
        @SuppressWarnings("unchecked")
        void passesThroughWhenLimiterUnavailable() throws Exception {
            ObjectProvider<ValkeyRateLimiter> emptyProvider = mock(ObjectProvider.class);
            when(emptyProvider.getIfAvailable()).thenReturn(null);
            ActionMemoRateLimitFilter beanlessFilter = new ActionMemoRateLimitFilter(emptyProvider);

            MockHttpServletRequest request = request("POST", "/api/v1/action-memos", "10.0.7.1");
            MockHttpServletResponse response = new MockHttpServletResponse();
            beanlessFilter.doFilter(request, response, new MockFilterChain());

            assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        }
    }
}
