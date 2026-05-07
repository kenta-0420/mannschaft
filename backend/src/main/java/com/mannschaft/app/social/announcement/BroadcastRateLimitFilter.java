package com.mannschaft.app.social.announcement;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
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
 * 告知ウィザード broadcast エンドポイントのユーザー別レートリミットフィルタ（F02.8）。
 *
 * <p>以下のエンドポイントに対してユーザー単位のレートリミットを適用する:</p>
 * <ul>
 *   <li>告知ウィザード実行 ({@code POST /api/v1/(teams|organizations)/*&#47;broadcast}):
 *       5分あたり5件 / ユーザー</li>
 * </ul>
 *
 * <p><b>キャッシュ戦略</b>: Caffeine の expireAfterAccess=10分 + maximumSize=10000。
 * {@link QuickMemoRateLimitFilter} と同一パターン。</p>
 */
@Component
public class BroadcastRateLimitFilter extends OncePerRequestFilter {

    /** broadcast エンドポイントを判定するパターン */
    private static final Pattern BROADCAST_PATTERN =
            Pattern.compile("^/api/v1/(teams|organizations)/[^/]+/broadcast$");

    /** バケット保持期間（非アクセス時）。レート窓（5分）より十分長く、OOM は防ぐ。 */
    private static final Duration BUCKET_TTL = Duration.ofMinutes(10);

    /** キャッシュ最大エントリ数。LRU で古いものから淘汰する。 */
    private static final long MAX_BUCKETS = 10_000L;

    /** broadcast 用バケットキャッシュ */
    private final Cache<String, Bucket> broadcastBuckets;

    public BroadcastRateLimitFilter() {
        this.broadcastBuckets = Caffeine.<String, Bucket>newBuilder()
                .expireAfterAccess(BUCKET_TTL)
                .maximumSize(MAX_BUCKETS)
                .build();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getServletPath();

        // GET は除外
        if ("GET".equalsIgnoreCase(method)) {
            return true;
        }

        // 認証なしは除外（認証フィルタで処理される）
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || "anonymousUser".equals(auth.getPrincipal())) {
            return true;
        }

        // broadcast エンドポイント以外はスキップ
        return !BROADCAST_PATTERN.matcher(path).matches();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String userKey = resolveUserKey(request);
        Bucket bucket = broadcastBuckets.get(userKey, k -> newBroadcastBucket());

        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
        } else {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", "300");
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"error\":\"RATE_LIMIT_EXCEEDED\"," +
                    "\"message\":\"リクエストが多すぎます。しばらく待ってから再試行してください。\"}");
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

    /**
     * broadcast 用バケットを生成する（5分あたり5件）。
     */
    private Bucket newBroadcastBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.classic(5, Refill.greedy(5, Duration.ofMinutes(5))))
                .build();
    }
}
