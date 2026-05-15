package com.mannschaft.app.favorite.filter;

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
 * F02.9 お気に入りウィジェットのユーザー別レートリミットフィルタ。
 *
 * <p>設計書: {@code docs/features/F02.9_favorites_widget.md}
 *
 * <p>本フィルタが扱う対象:
 * <ul>
 *   <li>{@code GET    /api/v1/me/favorites}         ─ 120 req/分</li>
 *   <li>{@code POST   /api/v1/me/favorites}         ─ 30 req/時</li>
 *   <li>{@code DELETE /api/v1/me/favorites/{id}}    ─ 60 req/時</li>
 *   <li>{@code PATCH  /api/v1/me/favorites/reorder} ─ 30 req/時</li>
 * </ul>
 *
 * <p>パターンは「具体度の高いものから順に」評価する。
 * {@code /reorder} を {@code /{id}} より先に判定すること。
 *
 * <p>キャッシュ戦略: Caffeine の expireAfterAccess=2 時間 + maximumSize=10000。
 * {@link com.mannschaft.app.pointcard.filter.PointCardRateLimitFilter} と同一パターン。
 */
@Component
public class FavoriteRateLimitFilter extends OncePerRequestFilter {

    // ──── レート定義 ─────────────────────────────
    private static final int LIST_RATE_PER_MINUTE = 120;
    private static final int ADD_RATE_PER_HOUR = 30;
    private static final int DELETE_RATE_PER_HOUR = 60;
    private static final int REORDER_RATE_PER_HOUR = 30;

    private static final Duration BUCKET_TTL = Duration.ofHours(2);
    private static final long MAX_BUCKETS = 10_000L;

    // ──── パスパターン ───────────────────────────

    /** 一覧取得 (GET) / 追加 (POST) の共通パス。 */
    private static final Pattern FAVORITES_ROOT_PATH =
            Pattern.compile("^/api/v1/me/favorites$");

    /** 並び替え: /{id} パターンより先に判定する。 */
    private static final Pattern REORDER_PATH =
            Pattern.compile("^/api/v1/me/favorites/reorder$");

    /** 削除 (DELETE) / 1件取得 (GET) の {@code /{id}} パターン。 */
    private static final Pattern FAVORITE_ID_PATH =
            Pattern.compile("^/api/v1/me/favorites/[0-9a-fA-F-]{36}$");

    // ──── バケット ──────────────────────────────
    private final Cache<String, Bucket> listBuckets;
    private final Cache<String, Bucket> addBuckets;
    private final Cache<String, Bucket> deleteBuckets;
    private final Cache<String, Bucket> reorderBuckets;

    public FavoriteRateLimitFilter() {
        this.listBuckets = newCache();
        this.addBuckets = newCache();
        this.deleteBuckets = newCache();
        this.reorderBuckets = newCache();
    }

    private static Cache<String, Bucket> newCache() {
        return Caffeine.<String, Bucket>newBuilder()
                .expireAfterAccess(BUCKET_TTL)
                .maximumSize(MAX_BUCKETS)
                .build();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 本フィルタの管理対象パスのみ通す（その他のリクエストは除外）
        String method = request.getMethod();
        String path = request.getServletPath();

        if (("GET".equalsIgnoreCase(method) || "POST".equalsIgnoreCase(method))
                && FAVORITES_ROOT_PATH.matcher(path).matches()) {
            return false;
        }
        if ("PATCH".equalsIgnoreCase(method) && REORDER_PATH.matcher(path).matches()) {
            return false;
        }
        if ("DELETE".equalsIgnoreCase(method) && FAVORITE_ID_PATH.matcher(path).matches()) {
            return false;
        }
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String method = request.getMethod();
        String path = request.getServletPath();
        String userKey = resolveUserKey(request);

        Bucket bucket;
        String retryAfter;

        // 評価順序: より具体的なものから判定する（reorder を /{id} より先に）
        if ("PATCH".equalsIgnoreCase(method) && REORDER_PATH.matcher(path).matches()) {
            bucket = reorderBuckets.get(userKey, k -> newBucketPerHour(REORDER_RATE_PER_HOUR));
            retryAfter = "3600";
        } else if ("DELETE".equalsIgnoreCase(method) && FAVORITE_ID_PATH.matcher(path).matches()) {
            bucket = deleteBuckets.get(userKey, k -> newBucketPerHour(DELETE_RATE_PER_HOUR));
            retryAfter = "3600";
        } else if ("POST".equalsIgnoreCase(method) && FAVORITES_ROOT_PATH.matcher(path).matches()) {
            bucket = addBuckets.get(userKey, k -> newBucketPerHour(ADD_RATE_PER_HOUR));
            retryAfter = "3600";
        } else if ("GET".equalsIgnoreCase(method) && FAVORITES_ROOT_PATH.matcher(path).matches()) {
            bucket = listBuckets.get(userKey, k -> newBucketPerMinute(LIST_RATE_PER_MINUTE));
            retryAfter = "60";
        } else {
            chain.doFilter(request, response);
            return;
        }

        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
        } else {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", retryAfter);
        }
    }

    private String resolveUserKey(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getPrincipal())) {
            return "u:" + auth.getName();
        }
        return "ip:" + request.getRemoteAddr();
    }

    private Bucket newBucketPerMinute(int capacity) {
        return Bucket.builder()
                .addLimit(Bandwidth.simple(capacity, Duration.ofMinutes(1)))
                .build();
    }

    private Bucket newBucketPerHour(int capacity) {
        return Bucket.builder()
                .addLimit(Bandwidth.simple(capacity, Duration.ofHours(1)))
                .build();
    }
}
