package com.mannschaft.app.social.announcement;

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
 * {@link AnnouncementReadRateLimitFilter} のユニットテスト（#2494 課題 2）。
 *
 * <p>金型は同ドメインの {@code BroadcastRateLimitFilterTest}。
 * 単件既読 60 req/分・一括既読 5 req/分 が<b>別枠</b>で効くこと、
 * 閾値内は従来どおり通ること（非回帰）、超過時の応答が既存フィルタと同じ
 * 429 + {@code Retry-After} + {@code X-RateLimit-*} + JSON ボディであることを固定する。</p>
 */
@DisplayName("AnnouncementReadRateLimitFilter — 既読EPの流量制限（#2494）")
class AnnouncementReadRateLimitFilterTest {

    private static final long RESET_EPOCH = 1_750_000_060L;
    private static final long RETRY_AFTER = 60L;

    private AnnouncementReadRateLimitFilter filter;
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
        filter = new AnnouncementReadRateLimitFilter(provider);
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

    private MockHttpServletRequest post(String path) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
        req.setServletPath(path);
        req.setRemoteAddr("10.9.0.1");
        return req;
    }

    private MockHttpServletRequest singleRead(String scopeType, String scopeId, String announcementId) {
        return post("/api/v1/" + scopeType + "/" + scopeId + "/announcements/" + announcementId + "/read");
    }

    private MockHttpServletRequest readAll(String scopeType, String scopeId) {
        return post("/api/v1/" + scopeType + "/" + scopeId + "/announcements/read-all");
    }

    @Nested
    @DisplayName("一括既読 — 5 req/分（単件より厳しい）")
    class ReadAllLimit {

        @Test
        @DisplayName("閾値内は通り、超過で 429 / Retry-After / X-RateLimit-* / JSON ボディ")
        void 超過で429() throws Exception {
            authenticateAs("user-alice");

            for (int i = 0; i < AnnouncementReadRateLimitFilter.READ_ALL_LIMIT; i++) {
                assertThat(invoke(readAll("teams", "1")).getStatus())
                        .as("read-all POST #%d は通ること（非回帰）", i + 1)
                        .isEqualTo(HttpStatus.OK.value());
            }

            MockHttpServletResponse overLimit = invoke(readAll("teams", "1"));
            assertThat(overLimit.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
            assertThat(overLimit.getHeader("Retry-After")).isEqualTo(String.valueOf(RETRY_AFTER));
            assertThat(overLimit.getHeader("X-RateLimit-Limit"))
                    .isEqualTo(String.valueOf(AnnouncementReadRateLimitFilter.READ_ALL_LIMIT));
            assertThat(overLimit.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
            assertThat(overLimit.getHeader("X-RateLimit-Reset")).isEqualTo(String.valueOf(RESET_EPOCH));
            assertThat(overLimit.getContentAsString()).contains("Too many requests");

            verify(rateLimiter, atLeastOnce()).tryConsume(
                    eq(AnnouncementReadRateLimitFilter.ZONE_READ_ALL), eq("u:user-alice"),
                    eq(AnnouncementReadRateLimitFilter.READ_ALL_LIMIT), eq(Duration.ofMinutes(1)));
        }

        @Test
        @DisplayName("スコープを変えて回しても同一 zone・同一ユーザーキーで合算される（濫用対策の要）")
        void スコープを変えても合算される() throws Exception {
            authenticateAs("user-bob");

            // teams/organizations とスコープ ID をばらけさせても上限は共有される
            for (int i = 0; i < AnnouncementReadRateLimitFilter.READ_ALL_LIMIT; i++) {
                String scopeType = (i % 2 == 0) ? "teams" : "organizations";
                assertThat(invoke(readAll(scopeType, String.valueOf(i))).getStatus())
                        .isEqualTo(HttpStatus.OK.value());
            }
            assertThat(invoke(readAll("teams", "999")).getStatus())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        }

        @Test
        @DisplayName("ユーザーが違えば互いの枠を消費しない")
        void ユーザー別にカウントされる() throws Exception {
            authenticateAs("user-carol");
            for (int i = 0; i < AnnouncementReadRateLimitFilter.READ_ALL_LIMIT; i++) {
                assertThat(invoke(readAll("teams", "1")).getStatus()).isEqualTo(HttpStatus.OK.value());
            }
            assertThat(invoke(readAll("teams", "1")).getStatus())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());

            authenticateAs("user-dave");
            assertThat(invoke(readAll("teams", "1")).getStatus()).isEqualTo(HttpStatus.OK.value());
        }
    }

    @Nested
    @DisplayName("単件既読 — 60 req/分")
    class SingleReadLimit {

        @Test
        @DisplayName("閾値内は通り、超過で 429")
        void 超過で429() throws Exception {
            authenticateAs("user-erin");

            for (int i = 0; i < AnnouncementReadRateLimitFilter.SINGLE_READ_LIMIT; i++) {
                assertThat(invoke(singleRead("teams", "1", String.valueOf(i))).getStatus())
                        .as("単件既読 POST #%d は通ること（非回帰）", i + 1)
                        .isEqualTo(HttpStatus.OK.value());
            }
            assertThat(invoke(singleRead("teams", "1", "999")).getStatus())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());

            verify(rateLimiter, atLeastOnce()).tryConsume(
                    eq(AnnouncementReadRateLimitFilter.ZONE_SINGLE_READ), eq("u:user-erin"),
                    eq(AnnouncementReadRateLimitFilter.SINGLE_READ_LIMIT), eq(Duration.ofMinutes(1)));
        }

        @Test
        @DisplayName("単件既読を上限まで叩いても一括既読の枠は残る（zone 分離）")
        void zoneが分離されている() throws Exception {
            authenticateAs("user-frank");

            for (int i = 0; i < AnnouncementReadRateLimitFilter.SINGLE_READ_LIMIT; i++) {
                assertThat(invoke(singleRead("teams", "1", String.valueOf(i))).getStatus())
                        .isEqualTo(HttpStatus.OK.value());
            }
            assertThat(invoke(singleRead("teams", "1", "999")).getStatus())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());

            // 一括既読は別 zone なので影響を受けない
            assertThat(invoke(readAll("teams", "1")).getStatus()).isEqualTo(HttpStatus.OK.value());
        }

        @Test
        @DisplayName("組織スコープの単件既読も同一 zone で制限される")
        void 組織スコープも対象() throws Exception {
            authenticateAs("user-grace");
            assertThat(invoke(singleRead("organizations", "5", "77")).getStatus())
                    .isEqualTo(HttpStatus.OK.value());
            verify(rateLimiter, atLeastOnce()).tryConsume(
                    eq(AnnouncementReadRateLimitFilter.ZONE_SINGLE_READ), eq("u:user-grace"),
                    eq(AnnouncementReadRateLimitFilter.SINGLE_READ_LIMIT), eq(Duration.ofMinutes(1)));
        }
    }

    @Nested
    @DisplayName("対象外リクエストはスキップされる")
    class SkippedRequests {

        @Test
        @DisplayName("お知らせ一覧取得（GET）はフィルタ対象外")
        void 一覧取得は対象外() {
            authenticateAs("user-alice");
            String path = "/api/v1/teams/1/announcements";
            MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
            req.setServletPath(path);
            assertThat(filter.shouldNotFilter(req)).isTrue();
        }

        @Test
        @DisplayName("お知らせ化（POST /announcements）はフィルタ対象外")
        void お知らせ化は対象外() {
            authenticateAs("user-alice");
            MockHttpServletRequest req = post("/api/v1/teams/1/announcements");
            assertThat(filter.shouldNotFilter(req)).isTrue();
        }

        @Test
        @DisplayName("ピン留め（PATCH /{id}/pin）はフィルタ対象外")
        void ピン留めは対象外() {
            authenticateAs("user-alice");
            String path = "/api/v1/teams/1/announcements/5/pin";
            MockHttpServletRequest req = new MockHttpServletRequest("PATCH", path);
            req.setServletPath(path);
            assertThat(filter.shouldNotFilter(req)).isTrue();
        }

        @Test
        @DisplayName("未認証は shouldNotFilter=true（認証フィルタに委ねる）")
        void 未認証は対象外() {
            MockHttpServletRequest req = readAll("teams", "1");
            assertThat(filter.shouldNotFilter(req)).isTrue();
        }

        @Test
        @DisplayName("別ドメインの read エンドポイントは対象外")
        void 別ドメインのreadは対象外() {
            authenticateAs("user-alice");
            MockHttpServletRequest req = post("/api/v1/teams/1/bulletins/5/read");
            assertThat(filter.shouldNotFilter(req)).isTrue();
        }

        @Test
        @DisplayName("既読EPは shouldNotFilter=false（取りこぼしていないことの裏取り）")
        void 既読EPは対象内() {
            authenticateAs("user-alice");
            assertThat(filter.shouldNotFilter(readAll("teams", "1"))).isFalse();
            assertThat(filter.shouldNotFilter(readAll("organizations", "1"))).isFalse();
            assertThat(filter.shouldNotFilter(singleRead("teams", "1", "9"))).isFalse();
            assertThat(filter.shouldNotFilter(singleRead("organizations", "1", "9"))).isFalse();
        }
    }

    @Nested
    @DisplayName("ValkeyRateLimiter Bean 不在（最小テストコンテキスト互換）")
    class LimiterBeanAbsent {

        @Test
        @DisplayName("ValkeyRateLimiter が解決できない場合は素通しする")
        @SuppressWarnings("unchecked")
        void 素通しする() throws Exception {
            authenticateAs("user-alice");
            ObjectProvider<ValkeyRateLimiter> emptyProvider = mock(ObjectProvider.class);
            when(emptyProvider.getIfAvailable()).thenReturn(null);
            AnnouncementReadRateLimitFilter beanlessFilter =
                    new AnnouncementReadRateLimitFilter(emptyProvider);

            MockHttpServletResponse response = new MockHttpServletResponse();
            beanlessFilter.doFilter(readAll("teams", "1"), response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        }
    }
}
