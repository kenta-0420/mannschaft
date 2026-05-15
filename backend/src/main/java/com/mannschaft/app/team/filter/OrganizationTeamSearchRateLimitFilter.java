package com.mannschaft.app.team.filter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * F15.4: 組織内チーム（店舗）検索エンドポイントのレート制限フィルタ。
 *
 * <p>設計書: {@code docs/features/F15.4_team_store_search_within_org.md} §3.5 / §6
 *
 * <p>対象エンドポイント: {@code GET /api/v1/organizations/{orgId}/teams/search}（permitAll）
 *
 * <p>レート上限:
 * <ul>
 *   <li>未ログイン: <strong>30 req / 分 / IP</strong></li>
 *   <li>ログイン: <strong>120 req / 分 / userId</strong></li>
 * </ul>
 *
 * <p>キャッシュ戦略: Caffeine の {@code expireAfterAccess=2 時間} + {@code maximumSize=10_000}。
 * 構造は {@code PointCardRateLimitFilter} と同形。
 */
@Component
public class OrganizationTeamSearchRateLimitFilter extends OncePerRequestFilter {

    // ──── レート定義 ─────────────────────────────
    private static final int AUTHENTICATED_RATE_PER_MINUTE = 120;
    private static final int ANONYMOUS_RATE_PER_MINUTE = 30;

    private static final Duration BUCKET_TTL = Duration.ofHours(2);
    private static final long MAX_BUCKETS = 10_000L;

    // ──── パスパターン ───────────────────────────
    /** {@code /api/v1/organizations/{orgId}/teams/search} の GET のみマッチ。 */
    private static final Pattern ORG_TEAM_SEARCH_PATH =
            Pattern.compile("^/api/v1/organizations/[^/]+/teams/search$");

    // ──── バケット ──────────────────────────────
    /** 認証済みユーザーのバケット（キー: {@code "u:" + userId}）。 */
    private final Cache<String, Bucket> authenticatedBuckets;
    /** 未認証アクセスのバケット（キー: {@code "ip:" + remoteAddr}）。 */
    private final Cache<String, Bucket> anonymousBuckets;

    public OrganizationTeamSearchRateLimitFilter() {
        this.authenticatedBuckets = newCache();
        this.anonymousBuckets = newCache();
    }

    private static Cache<String, Bucket> newCache() {
        return Caffeine.<String, Bucket>newBuilder()
                .expireAfterAccess(BUCKET_TTL)
                .maximumSize(MAX_BUCKETS)
                .build();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 対象パス以外は全てスキップ
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        return !ORG_TEAM_SEARCH_PATH.matcher(request.getServletPath()).matches();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean authenticated = auth != null
                && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getPrincipal());

        Bucket bucket;
        if (authenticated) {
            String key = "u:" + auth.getName();
            bucket = authenticatedBuckets.get(key,
                    k -> newBucketPerMinute(AUTHENTICATED_RATE_PER_MINUTE));
        } else {
            String key = "ip:" + request.getRemoteAddr();
            bucket = anonymousBuckets.get(key,
                    k -> newBucketPerMinute(ANONYMOUS_RATE_PER_MINUTE));
        }

        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
        } else {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", "60");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"error\":\"Too many requests\"}");
        }
    }

    private Bucket newBucketPerMinute(int capacity) {
        return Bucket.builder()
                .addLimit(Bandwidth.simple(capacity, Duration.ofMinutes(1)))
                .build();
    }
}
