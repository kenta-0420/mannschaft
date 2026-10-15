package com.mannschaft.app.team.filter;

import com.mannschaft.app.common.ratelimit.RateLimitResult;
import com.mannschaft.app.common.ratelimit.ValkeyRateLimiter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F01.2.1 AC-G102（申請 10件/時/ユーザー）— {@link TeamOrgApplicationRateLimitFilter} のユニットテスト。
 *
 * <p>実 Valkey / Docker 不要。モックの {@link ValkeyRateLimiter} を in-memory カウンタに差し替え、
 * 429 の境界・対象パス・キーの単位・ウィンドウ長を決定論的に確かめる（金型: 村招待受諾のレートリミットフィルタのテスト）。
 * 実 HTTP 経路への結線は {@code TeamOrgApplicationRateLimitWiringIT} が確かめる。</p>
 */
@DisplayName("F01.2.1 AC-G102 チームの加盟申請 レートリミットフィルタ（10件/時/ユーザー）")
class TeamOrgApplicationRateLimitFilterTest {

    private static final long RESET_EPOCH = 1_750_003_600L;
    private static final long RETRY_AFTER = 3600L;
    private static final int LIMIT = 10;

    private TeamOrgApplicationRateLimitFilter filter;
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
                    long count = counters.computeIfAbsent(zone + "|" + key, k -> new AtomicLong()).incrementAndGet();
                    return new RateLimitResult(count <= limit, limit, Math.max(0, limit - count),
                            RESET_EPOCH, RETRY_AFTER);
                });
        ObjectProvider<ValkeyRateLimiter> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(rateLimiter);
        filter = new TeamOrgApplicationRateLimitFilter(provider);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        counters.clear();
    }

    @Test
    @DisplayName("同一ユーザーは 1 時間に 10 回まで通過し、11 回目で 429（Retry-After 付き）")
    void 十一回目は429() throws Exception {
        authenticateAs("42");

        for (int i = 0; i < LIMIT; i++) {
            assertThat(invoke(applyPost("team-" + i)).getStatus())
                    .as("申請 #%d は通過する", i + 1).isEqualTo(HttpStatus.OK.value());
        }

        MockHttpServletResponse over = invoke(applyPost("team-over"));
        assertThat(over.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(over.getHeader("Retry-After")).isEqualTo(String.valueOf(RETRY_AFTER));
        assertThat(over.getHeader("X-RateLimit-Limit")).isEqualTo(String.valueOf(LIMIT));
    }

    @Test
    @DisplayName("チームの slug が違っても同一ユーザーは同じカウンタ（チームを変えて回数制限を迂回できない）")
    void チームを変えても同じカウンタ() throws Exception {
        authenticateAs("42");

        for (int i = 0; i < LIMIT; i++) {
            invoke(applyPost("team-" + i));
        }

        assertThat(counters).as("カウンタはチーム別に分かれていない").hasSize(1);
    }

    @Test
    @DisplayName("別ユーザーのカウンタは独立している")
    void 別ユーザーは独立() throws Exception {
        authenticateAs("42");
        for (int i = 0; i < LIMIT + 1; i++) {
            invoke(applyPost("team-a"));
        }

        authenticateAs("99");
        assertThat(invoke(applyPost("team-a")).getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    @DisplayName("申請の POST だけが対象。一覧の GET・取下げの DELETE・他のパスは数えない")
    void 対象は申請のPOSTだけ() throws Exception {
        authenticateAs("42");

        for (int i = 0; i < LIMIT + 5; i++) {
            assertThat(invoke(request("GET", "/api/v1/teams/team-a/org-applications")).getStatus())
                    .isEqualTo(HttpStatus.OK.value());
            assertThat(invoke(request("DELETE", "/api/v1/teams/team-a/org-applications/5")).getStatus())
                    .isEqualTo(HttpStatus.OK.value());
            assertThat(invoke(request("POST", "/api/v1/teams/team-a/org-invites/5/accept")).getStatus())
                    .isEqualTo(HttpStatus.OK.value());
        }

        assertThat(counters).as("対象外のリクエストはカウンタを消費しない").isEmpty();
    }

    @Test
    @DisplayName("未認証のリクエストは数えない（認証フィルタが 401 にする）")
    void 未認証は数えない() throws Exception {
        for (int i = 0; i < LIMIT + 5; i++) {
            assertThat(invoke(applyPost("team-a")).getStatus()).isEqualTo(HttpStatus.OK.value());
        }

        assertThat(counters).isEmpty();
    }

    // ---------------------------------------------------------------------
    // ヘルパー
    // ---------------------------------------------------------------------

    private MockHttpServletResponse invoke(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return response;
    }

    private MockHttpServletRequest applyPost(String teamSlug) {
        return request("POST", "/api/v1/teams/" + teamSlug + "/org-applications");
    }

    private MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest req = new MockHttpServletRequest(method, path);
        req.setServletPath(path);
        req.setRemoteAddr("10.0.0.1");
        return req;
    }

    private void authenticateAs(String userId) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                userId, "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }
}
