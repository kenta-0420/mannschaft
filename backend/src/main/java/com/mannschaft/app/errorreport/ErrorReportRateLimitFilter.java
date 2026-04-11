package com.mannschaft.app.errorreport;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
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

/**
 * エラーレポートAPIのIPベースレート制限フィルタ。
 * {@code POST /api/v1/error-reports} のみに適用し、他APIに影響しない。
 * 同一IPから 10 回/分。超過時は 429 Too Many Requests。
 *
 * <p><b>キャッシュ戦略</b>: Caffeine の {@code expireAfterAccess=10分} + {@code maximumSize=10000}。
 * 旧実装の {@code ConcurrentHashMap} は Eviction ポリシーが無く、IP ごとにバケットが永続化されるため
 * 長期稼働で OOM を引き起こす懸念があった。{@link com.mannschaft.app.sync.SyncRateLimitFilter}
 * と同一パターンで統一することで保守性を確保する。</p>
 * <ul>
 *   <li>10 分間アクセスが無いバケットは自動削除される（非アクティブな IP はメモリから消える）</li>
 *   <li>最大 10000 エントリを超えると LRU で淘汰される（想定外のキー爆発を防ぐ）</li>
 *   <li>レートリミット窓は Bucket4j の Bandwidth（1分）側が管理するため、
 *       10 分の TTL はメモリ保持期間に関する上限であって仕様に影響しない</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ErrorReportRateLimitFilter extends OncePerRequestFilter {

    private static final int LIMIT_PER_MINUTE = 10;

    /** バケット保持期間（非アクセス時）。レート窓（1分）より十分長く、OOM は防ぐ。 */
    private static final Duration BUCKET_TTL = Duration.ofMinutes(10);

    /** キャッシュ最大エントリ数。想定外のキー爆発時に LRU で古いものから淘汰する。 */
    private static final long MAX_BUCKETS = 10_000L;

    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(BUCKET_TTL)
            .maximumSize(MAX_BUCKETS)
            .build();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"/api/v1/error-reports".equals(request.getServletPath())
                || !"POST".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {
        String key = "error-report-rate:" + request.getRemoteAddr();
        Bucket bucket = resolveBucket(key);

        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
        } else {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        }
    }

    private Bucket resolveBucket(String key) {
        return buckets.get(key, k -> Bucket.builder()
                .addLimit(Bandwidth.simple(LIMIT_PER_MINUTE, Duration.ofMinutes(1)))
                .build());
    }
}
