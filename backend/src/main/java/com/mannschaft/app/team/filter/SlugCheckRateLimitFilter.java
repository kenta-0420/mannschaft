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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * チーム・組織スラッグ可用性チェック API のユーザー別レートリミットフィルタ。
 *
 * <p>以下のエンドポイントに対してユーザー単位のレートリミットを適用する:</p>
 * <ul>
 *   <li>{@code GET /api/v1/teams/slug-check}: 60 req/分</li>
 *   <li>{@code GET /api/v1/organizations/slug-check}: 60 req/分</li>
 * </ul>
 *
 * <p><b>キャッシュ戦略</b>: Caffeine の expireAfterAccess=10分 + maximumSize=10000。
 * {@link com.mannschaft.app.actionmemo.ActionMemoRateLimitFilter} と同一パターン。</p>
 */
@Component
public class SlugCheckRateLimitFilter extends OncePerRequestFilter {

    /** スラッグチェック操作のレート制限 (req/分) */
    private static final int RATE_PER_MINUTE = 60;

    /** バケット保持期間（非アクセス時）。レート窓（1分）より十分長く、OOM は防ぐ。 */
    private static final Duration BUCKET_TTL = Duration.ofMinutes(10);

    /** キャッシュ最大エントリ数。LRU で古いものから淘汰する。 */
    private static final long MAX_BUCKETS = 10_000L;

    private static final String TEAMS_SLUG_CHECK_PATH = "/api/v1/teams/slug-check";
    private static final String ORGS_SLUG_CHECK_PATH = "/api/v1/organizations/slug-check";

    /** スラッグチェック操作用バケットキャッシュ */
    private final Cache<String, Bucket> slugCheckBuckets;

    public SlugCheckRateLimitFilter() {
        this.slugCheckBuckets = Caffeine.<String, Bucket>newBuilder()
                .expireAfterAccess(BUCKET_TTL)
                .maximumSize(MAX_BUCKETS)
                .build();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return !TEAMS_SLUG_CHECK_PATH.equals(path) && !ORGS_SLUG_CHECK_PATH.equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {
        String userKey = resolveUserKey(request);
        Bucket bucket = slugCheckBuckets.get(userKey, k -> newBucket());

        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
        } else {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", "60");
        }
    }

    /**
     * 認証済みなら userId を、未認証なら IP をキーにする。
     */
    private String resolveUserKey(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getPrincipal())) {
            return "u:" + auth.getName();
        }
        return "ip:" + request.getRemoteAddr();
    }

    private Bucket newBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.simple(RATE_PER_MINUTE, Duration.ofMinutes(1)))
                .build();
    }
}
