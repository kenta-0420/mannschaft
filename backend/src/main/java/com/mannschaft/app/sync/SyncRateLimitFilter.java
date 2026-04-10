package com.mannschaft.app.sync;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * F11.1 オフライン同期APIのIPベースレート制限フィルタ。
 *
 * <ul>
 *   <li>POST /api/v1/sync: 1分10回（一括同期は重い処理のため厳しめ）</li>
 *   <li>GET/PATCH/DELETE /api/v1/sync/conflicts/**: 1分60回</li>
 * </ul>
 *
 * <p>ConcurrentHashMap ベースのインメモリ実装。
 * ErrorReportRateLimitFilter と同じパターン。</p>
 */
@Component
@RequiredArgsConstructor
public class SyncRateLimitFilter extends OncePerRequestFilter {

    private static final String SYNC_PATH = "/api/v1/sync";
    private static final int SYNC_LIMIT = 10;
    private static final int CONFLICT_LIMIT = 60;

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return !path.startsWith(SYNC_PATH);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {
        String path = request.getServletPath();
        String method = request.getMethod();
        String ip = request.getRemoteAddr();

        String bucketKey;
        int limit;

        if (SYNC_PATH.equals(path) && "POST".equalsIgnoreCase(method)) {
            // POST /api/v1/sync: 1分10回
            bucketKey = "sync-post:" + ip;
            limit = SYNC_LIMIT;
        } else if (path.startsWith(SYNC_PATH + "/conflicts")) {
            // conflicts 系: 1分60回
            bucketKey = "sync-conflicts:" + ip;
            limit = CONFLICT_LIMIT;
        } else {
            chain.doFilter(request, response);
            return;
        }

        Bucket bucket = resolveBucket(bucketKey, limit);
        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
        } else {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        }
    }

    private Bucket resolveBucket(String key, int limit) {
        return buckets.computeIfAbsent(key, k -> Bucket.builder()
                .addLimit(Bandwidth.simple(limit, Duration.ofMinutes(1)))
                .build());
    }
}
