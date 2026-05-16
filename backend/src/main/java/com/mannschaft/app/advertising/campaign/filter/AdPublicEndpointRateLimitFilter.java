package com.mannschaft.app.advertising.campaign.filter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * F09.17 Phase 11-b 公開エンドポイント (unsubscribe / 開封ピクセル) の IP 単位レートリミット。
 *
 * <p>対象:</p>
 * <ul>
 *   <li>{@code GET /api/v1/ads/unsubscribe}     ─ 60 req/分（設計書 §6）</li>
 *   <li>{@code GET /api/v1/ads/pixels/open}     ─ 600 req/分（メーラー再フェッチ考慮）</li>
 * </ul>
 *
 * <p>認証不要エンドポイントのためユーザー識別子が無く、IP アドレスのみで制御する。
 * X-Forwarded-For は経路に reverse proxy がある場合のみ意味があるため、
 * 先頭値があれば使用しつつ {@code request.getRemoteAddr()} にフォールバックする。</p>
 *
 * <p>本フィルタは 60/分の unsubscribe と 600/分の pixel をそれぞれ別キャッシュで管理し、
 * 一方の枯渇が他方に波及しないようにする。</p>
 */
@Component
public class AdPublicEndpointRateLimitFilter extends OncePerRequestFilter {

    private static final String UNSUBSCRIBE_PATH = "/api/v1/ads/unsubscribe";
    private static final String OPEN_PIXEL_PATH = "/api/v1/ads/pixels/open";

    private static final int UNSUBSCRIBE_RATE_PER_MINUTE = 60;
    private static final int OPEN_PIXEL_RATE_PER_MINUTE = 600;

    private static final Duration BUCKET_TTL = Duration.ofHours(2);
    private static final long MAX_BUCKETS = 50_000L;

    private final Cache<String, Bucket> unsubscribeBuckets;
    private final Cache<String, Bucket> openPixelBuckets;

    public AdPublicEndpointRateLimitFilter() {
        this.unsubscribeBuckets = newCache();
        this.openPixelBuckets = newCache();
    }

    private static Cache<String, Bucket> newCache() {
        return Caffeine.<String, Bucket>newBuilder()
                .expireAfterAccess(BUCKET_TTL)
                .maximumSize(MAX_BUCKETS)
                .build();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getServletPath();
        return !UNSUBSCRIBE_PATH.equals(path) && !OPEN_PIXEL_PATH.equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getServletPath();
        String ipKey = "ip:" + resolveIp(request);

        Bucket bucket;
        if (UNSUBSCRIBE_PATH.equals(path)) {
            bucket = unsubscribeBuckets.get(ipKey,
                    k -> newBucketPerMinute(UNSUBSCRIBE_RATE_PER_MINUTE));
        } else {
            bucket = openPixelBuckets.get(ipKey,
                    k -> newBucketPerMinute(OPEN_PIXEL_RATE_PER_MINUTE));
        }

        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
        } else {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", "60");
        }
    }

    private static String resolveIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return request.getRemoteAddr();
    }

    private static Bucket newBucketPerMinute(int capacity) {
        return Bucket.builder()
                .addLimit(Bandwidth.simple(capacity, Duration.ofMinutes(1)))
                .build();
    }
}
