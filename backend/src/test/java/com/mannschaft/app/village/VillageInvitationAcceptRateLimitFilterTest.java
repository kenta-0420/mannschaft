package com.mannschaft.app.village;

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
 * 依頼書 §5.5 — {@link VillageInvitationAcceptRateLimitFilter} のユニットテスト。
 *
 * <p>金型: {@code VillageAffinityRateLimitFilterTest}（{@link MockFilterChain} ＋ モック
 * {@link ValkeyRateLimiter} を in-memory カウントに差し替え・{@code setServletPath} で対象判定）。
 * 実 Valkey / Docker 不要で決定論的に 429 境界を検証できる。</p>
 *
 * <p>本フィルタの要点は<strong>キーにトークンを含めない</strong>こと。総当たりは毎回異なる
 * トークンを試すため、トークンをキーに含めるとカウンタが毎回新規になり永久に上限へ届かない。</p>
 */
class VillageInvitationAcceptRateLimitFilterTest {

    private static final long RESET_EPOCH = 1_750_000_030L;
    private static final long RETRY_AFTER = 30L;
    private static final int LIMIT = 10;

    private VillageInvitationAcceptRateLimitFilter filter;
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
                    long count = counters
                            .computeIfAbsent(zone + "|" + key, k -> new AtomicLong())
                            .incrementAndGet();
                    return new RateLimitResult(
                            count <= limit, limit, Math.max(0, limit - count), RESET_EPOCH, RETRY_AFTER);
                });

        ObjectProvider<ValkeyRateLimiter> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(rateLimiter);
        filter = new VillageInvitationAcceptRateLimitFilter(provider);
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

    private MockHttpServletRequest acceptPost(String token) {
        String path = "/api/v1/village-invitations/" + token + "/accept";
        MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
        req.setServletPath(path);
        req.setRemoteAddr("10.0.0.1");
        return req;
    }

    private void authenticateAs(String userId) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                userId, "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @Test
    @DisplayName("同一ユーザーは 10 回まで通過し、11 回目で 429（Retry-After 付き）")
    void exceedsLimitReturns429() throws Exception {
        authenticateAs("42");

        for (int i = 0; i < LIMIT; i++) {
            assertThat(invoke(acceptPost("token-" + i)).getStatus())
                    .as("accept #%d は通過するはず", i + 1)
                    .isEqualTo(HttpStatus.OK.value());
        }

        MockHttpServletResponse over = invoke(acceptPost("token-over"));
        assertThat(over.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(over.getHeader("Retry-After")).isEqualTo(String.valueOf(RETRY_AFTER));
        assertThat(over.getHeader("X-RateLimit-Limit")).isEqualTo(String.valueOf(LIMIT));
    }

    @Test
    @DisplayName("トークンが毎回違っても同一ユーザーなら同じカウンタ（総当たりを取り逃がさない）")
    void differentTokensShareTheSameCounter() throws Exception {
        authenticateAs("42");

        for (int i = 0; i < LIMIT; i++) {
            invoke(acceptPost("brute-force-attempt-" + i));
        }

        assertThat(counters).as("カウンタはトークン別に分かれていないこと").hasSize(1);
        assertThat(invoke(acceptPost("brute-force-attempt-final")).getStatus())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    @Test
    @DisplayName("別ユーザーのカウンタは独立している")
    void countersAreIndependentPerUser() throws Exception {
        authenticateAs("42");
        for (int i = 0; i < LIMIT; i++) {
            invoke(acceptPost("t" + i));
        }
        assertThat(invoke(acceptPost("t-over")).getStatus())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());

        authenticateAs("99");
        assertThat(invoke(acceptPost("t0")).getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    @DisplayName("対象外パス（発行・一覧・失効）は素通しし、カウントもしない")
    void nonTargetPathsAreUntouched() throws Exception {
        authenticateAs("42");

        String listPath = "/api/v1/villages/0192a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b/invitations";
        MockHttpServletRequest list = new MockHttpServletRequest("GET", listPath);
        list.setServletPath(listPath);
        assertThat(invoke(list).getStatus()).isEqualTo(HttpStatus.OK.value());

        // 受諾パスでも GET なら対象外（POST のみを絞る）。
        MockHttpServletRequest get = acceptPost("t0");
        get.setMethod("GET");
        assertThat(invoke(get).getStatus()).isEqualTo(HttpStatus.OK.value());

        assertThat(counters).as("対象外はカウンタを作らないこと").isEmpty();
    }
}
