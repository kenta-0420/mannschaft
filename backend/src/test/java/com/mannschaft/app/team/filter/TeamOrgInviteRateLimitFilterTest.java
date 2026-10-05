package com.mannschaft.app.team.filter;

import com.mannschaft.app.common.ratelimit.RateLimitResult;
import com.mannschaft.app.common.ratelimit.ValkeyRateLimiter;
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
 * F01.2.1 AC-G102（招待 30件/時/ユーザー）— {@link TeamOrgInviteRateLimitFilter} のユニットテスト。
 *
 * <p>実 Valkey / Docker 不要。モックの {@link ValkeyRateLimiter} を in-memory カウンタに差し替え、
 * 429 の境界・対象パス・キーの単位を決定論的に確かめる（金型: {@code TeamOrgApplicationRateLimitFilterTest}）。</p>
 */
@DisplayName("F01.2.1 AC-G102 組織からの加盟招待 レートリミットフィルタ（30件/時/ユーザー）")
class TeamOrgInviteRateLimitFilterTest {

    private static final long RESET_EPOCH = 1_750_003_600L;
    private static final long RETRY_AFTER = 3600L;
    private static final int LIMIT = 30;

    private TeamOrgInviteRateLimitFilter filter;
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ValkeyRateLimiter rateLimiter = mock(ValkeyRateLimiter.class);
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
        filter = new TeamOrgInviteRateLimitFilter(provider);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        counters.clear();
    }

    @Test
    @DisplayName("同一ユーザーは 1 時間に 30 回まで通過し、31 回目で 429（組織を変えても同じカウンタ）")
    void 三十一回目は429() throws Exception {
        authenticateAs("42");

        for (int i = 0; i < LIMIT; i++) {
            assertThat(invoke(request("POST", "/api/v1/organizations/org-" + (i % 3) + "/team-invites")).getStatus())
                    .as("招待 #%d は通過する", i + 1).isEqualTo(HttpStatus.OK.value());
        }

        MockHttpServletResponse over = invoke(request("POST", "/api/v1/organizations/org-x/team-invites"));
        assertThat(over.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(over.getHeader("Retry-After")).isEqualTo(String.valueOf(RETRY_AFTER));
        assertThat(counters).as("カウンタは組織別に分かれていない").hasSize(1);
    }

    @Test
    @DisplayName("招待の POST だけが対象。一覧の GET・取消の DELETE・チーム側の承諾は数えない")
    void 対象は招待のPOSTだけ() throws Exception {
        authenticateAs("42");

        for (int i = 0; i < LIMIT + 5; i++) {
            invoke(request("GET", "/api/v1/organizations/org-a/team-invites"));
            invoke(request("DELETE", "/api/v1/organizations/org-a/team-invites/team-a"));
            invoke(request("POST", "/api/v1/teams/team-a/org-invites/5/accept"));
        }

        assertThat(counters).as("対象外のリクエストはカウンタを消費しない").isEmpty();
    }

    @Test
    @DisplayName("未認証のリクエストは数えない（認証フィルタが 401 にする）")
    void 未認証は数えない() throws Exception {
        for (int i = 0; i < LIMIT + 5; i++) {
            invoke(request("POST", "/api/v1/organizations/org-a/team-invites"));
        }

        assertThat(counters).isEmpty();
    }

    private MockHttpServletResponse invoke(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
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
