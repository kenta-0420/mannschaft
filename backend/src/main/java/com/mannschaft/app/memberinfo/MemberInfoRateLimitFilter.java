package com.mannschaft.app.memberinfo;

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
import java.util.regex.Pattern;

/**
 * F14.2 チームメンバー情報機能のユーザー別レートリミットフィルタ。
 *
 * <p>{@code PUT /api/v1/teams/{teamId}/member-info/responses/me} に対して 10 req/分 の制限を適用する。</p>
 *
 * <p><b>キャッシュ戦略</b>: Caffeine の {@code expireAfterAccess=10分} + {@code maximumSize=10000}。
 * {@link com.mannschaft.app.actionmemo.ActionMemoRateLimitFilter} と同一パターン。</p>
 */
@Component
public class MemberInfoRateLimitFilter extends OncePerRequestFilter {

    private static final Pattern UPSERT_RESPONSES_PATTERN =
            Pattern.compile("^/api/v1/teams/[^/]+/member-info/responses/me$");

    private static final int RATE_PER_MINUTE = 10;

    /** バケット保持期間（非アクセス時）。レート窓（1分）より十分長く、OOM は防ぐ。 */
    private static final Duration BUCKET_TTL = Duration.ofMinutes(10);

    /** キャッシュ最大エントリ数。想定外のキー爆発時に LRU で古いものから淘汰する。 */
    private static final long MAX_BUCKETS = 10_000L;

    private final Cache<String, Bucket> buckets;

    public MemberInfoRateLimitFilter() {
        this.buckets = Caffeine.<String, Bucket>newBuilder()
                .expireAfterAccess(BUCKET_TTL)
                .maximumSize(MAX_BUCKETS)
                .build();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("PUT".equalsIgnoreCase(request.getMethod())
                && UPSERT_RESPONSES_PATTERN.matcher(request.getServletPath()).matches());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {
        String userKey = resolveUserKey(request);
        Bucket bucket = buckets.get(userKey, k -> newBucket());

        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
        } else {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", "60");
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"message\": \"リクエストが多すぎます。しばらく経ってから再試行してください。\"}");
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
