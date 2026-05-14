package com.mannschaft.app.pointcard.filter;

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
 * F18 ポイントカードウォレットのユーザー別レートリミットフィルタ。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §9.5
 *
 * <p>本フィルタが扱う対象（第二陣 2A 担当範囲）:
 * <ul>
 *   <li>{@code GET /api/v1/point-cards/providers}: 60 req/分</li>
 *   <li>{@code PUT /api/v1/point-cards/settings}: 10 req/時</li>
 * </ul>
 *
 * <p>残りのエンドポイント（カード CRUD・グループ・{@code /used} 等）は後続陣で
 * 同一フィルタへ追記する想定。本陣はパターンマッチで自分の責務のみを処理し、
 * 他エンドポイントはスルーする。
 *
 * <p>キャッシュ戦略: Caffeine の expireAfterAccess=10 分 + maximumSize=10000。
 * 既存 {@code QuickMemoRateLimitFilter} と同一パターン。
 */
@Component
public class PointCardRateLimitFilter extends OncePerRequestFilter {

    /** GET /providers のレート制限（req/分）。 */
    private static final int PROVIDERS_RATE_PER_MINUTE = 60;

    /** PUT /settings のレート制限（req/時）。 */
    private static final int SETTINGS_PUT_RATE_PER_HOUR = 10;

    /** バケット保持期間。レート窓（最大 1 時間）より十分長く、OOM は防ぐ。 */
    private static final Duration BUCKET_TTL = Duration.ofHours(2);

    private static final long MAX_BUCKETS = 10_000L;

    private static final Pattern PROVIDERS_PATH =
            Pattern.compile("^/api/v1/point-cards/providers$");

    private static final Pattern SETTINGS_PATH =
            Pattern.compile("^/api/v1/point-cards/settings$");

    private final Cache<String, Bucket> providersBuckets;
    private final Cache<String, Bucket> settingsBuckets;

    public PointCardRateLimitFilter() {
        this.providersBuckets = Caffeine.<String, Bucket>newBuilder()
                .expireAfterAccess(BUCKET_TTL)
                .maximumSize(MAX_BUCKETS)
                .build();
        this.settingsBuckets = Caffeine.<String, Bucket>newBuilder()
                .expireAfterAccess(BUCKET_TTL)
                .maximumSize(MAX_BUCKETS)
                .build();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getServletPath();

        if ("GET".equalsIgnoreCase(method) && PROVIDERS_PATH.matcher(path).matches()) {
            return false;
        }
        if ("PUT".equalsIgnoreCase(method) && SETTINGS_PATH.matcher(path).matches()) {
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
        if ("GET".equalsIgnoreCase(method) && PROVIDERS_PATH.matcher(path).matches()) {
            bucket = providersBuckets.get(userKey, k -> newBucketPerMinute(PROVIDERS_RATE_PER_MINUTE));
            retryAfter = "60";
        } else if ("PUT".equalsIgnoreCase(method) && SETTINGS_PATH.matcher(path).matches()) {
            bucket = settingsBuckets.get(userKey, k -> newBucketPerHour(SETTINGS_PUT_RATE_PER_HOUR));
            retryAfter = "3600";
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
