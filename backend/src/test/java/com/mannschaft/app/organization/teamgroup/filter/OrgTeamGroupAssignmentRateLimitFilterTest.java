package com.mannschaft.app.organization.teamgroup.filter;

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
 * F01.2.1 AC-G102（一括割当 20件/分/ユーザー）— {@link OrgTeamGroupAssignmentRateLimitFilter} のユニットテスト。
 *
 * <p>実 Valkey / Docker 不要。モックの {@link ValkeyRateLimiter} を in-memory カウンタに差し替え、
 * 429 の境界・対象パス・キーの単位を決定論的に確かめる。実 HTTP 経路への結線は
 * {@code OrgTeamGroupAssignmentRateLimitWiringIT} が確かめる。</p>
 */
@DisplayName("F01.2.1 AC-G102 一括割当 レートリミットフィルタ（20件/分/ユーザー）")
class OrgTeamGroupAssignmentRateLimitFilterTest {

    private static final long RESET_EPOCH = 1_750_000_060L;
    private static final long RETRY_AFTER = 60L;
    private static final int LIMIT = 20;

    private OrgTeamGroupAssignmentRateLimitFilter filter;
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
        filter = new OrgTeamGroupAssignmentRateLimitFilter(provider);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        counters.clear();
    }

    @Test
    @DisplayName("同一ユーザーは 1 分に 20 回まで通過し、21 回目で 429（Retry-After 付き）")
    void twentyFirstIs429() throws Exception {
        authenticateAs("42");

        for (int i = 0; i < LIMIT; i++) {
            assertThat(invoke(bulkPut("org-" + i)).getStatus())
                    .as("一括割当 #%d は通過する", i + 1).isEqualTo(HttpStatus.OK.value());
        }

        MockHttpServletResponse over = invoke(bulkPut("org-over"));
        assertThat(over.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(over.getHeader("Retry-After")).isEqualTo(String.valueOf(RETRY_AFTER));
        assertThat(over.getHeader("X-RateLimit-Limit")).isEqualTo(String.valueOf(LIMIT));
    }

    @Test
    @DisplayName("組織の slug が違っても同一ユーザーは同じカウンタ（組織を変えて回数制限を迂回できない）")
    void sameCounterAcrossOrganizations() throws Exception {
        authenticateAs("42");

        for (int i = 0; i < LIMIT; i++) {
            invoke(bulkPut("org-" + i));
        }

        assertThat(counters).as("カウンタは組織別に分かれていない").hasSize(1);
    }

    @Test
    @DisplayName("別ユーザーのカウンタは独立している")
    void otherUsersAreIndependent() throws Exception {
        authenticateAs("42");
        for (int i = 0; i < LIMIT + 1; i++) {
            invoke(bulkPut("org-a"));
        }

        authenticateAs("99");
        assertThat(invoke(bulkPut("org-a")).getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    @DisplayName("一括割当の PUT だけが対象。単体割当・一覧の GET・グループ管理・他のパスは数えない")
    void onlyBulkPutIsCounted() throws Exception {
        authenticateAs("42");

        for (int i = 0; i < LIMIT + 5; i++) {
            assertThat(invoke(request("PUT", "/api/v1/organizations/org-a/teams/team-a/team-group")).getStatus())
                    .isEqualTo(HttpStatus.OK.value());
            assertThat(invoke(request("GET", "/api/v1/organizations/org-a/teams")).getStatus())
                    .isEqualTo(HttpStatus.OK.value());
            assertThat(invoke(request("PUT", "/api/v1/organizations/org-a/team-groups/order")).getStatus())
                    .isEqualTo(HttpStatus.OK.value());
            assertThat(invoke(request("GET", "/api/v1/organizations/org-a/team-group-assignments")).getStatus())
                    .isEqualTo(HttpStatus.OK.value());
        }

        assertThat(counters).as("対象外のリクエストはカウンタを消費しない").isEmpty();
    }

    @Test
    @DisplayName("未認証のリクエストは数えない（認証フィルタが 401 にする）")
    void unauthenticatedIsNotCounted() throws Exception {
        for (int i = 0; i < LIMIT + 5; i++) {
            assertThat(invoke(bulkPut("org-a")).getStatus()).isEqualTo(HttpStatus.OK.value());
        }

        assertThat(counters).isEmpty();
    }

    private MockHttpServletResponse invoke(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return response;
    }

    private MockHttpServletRequest bulkPut(String orgSlug) {
        return request("PUT", "/api/v1/organizations/" + orgSlug + "/team-group-assignments");
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
