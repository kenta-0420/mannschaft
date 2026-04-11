package com.mannschaft.app.sync;

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
 * F11.1 オフライン同期APIのIPベースレート制限フィルタ。
 *
 * <ul>
 *   <li>POST /api/v1/sync: 1分10回（一括同期は重い処理のため厳しめ）</li>
 *   <li>GET/PATCH/DELETE /api/v1/sync/conflicts/**: 1分60回</li>
 * </ul>
 *
 * <p><b>キャッシュ戦略</b>: Caffeine の {@code expireAfterAccess=10分} + {@code maximumSize=10000}。
 * 旧実装の {@code ConcurrentHashMap} は Eviction ポリシーが無く、IP ごとにバケットが永続化されるため
 * 長期稼働で OOM を引き起こす懸念があった。Caffeine に差し替えることで:</p>
 * <ul>
 *   <li>10 分間アクセスが無いバケットは自動削除される（非アクティブな IP はメモリから消える）</li>
 *   <li>最大 10000 エントリを超えると LRU で淘汰される（想定外のキー爆発を防ぐ）</li>
 *   <li>レートリミット窓は Bucket4j の Bandwidth（1分）側が管理するため、
 *       10 分の TTL はメモリ保持期間に関する上限であって仕様に影響しない</li>
 * </ul>
 *
 * <p>sync / conflicts でキャッシュを分離しているのは、片方のトラフィックが
 * もう片方のエントリを LRU で押し出すのを避けるため。</p>
 */
@Component
@RequiredArgsConstructor
public class SyncRateLimitFilter extends OncePerRequestFilter {

    private static final String SYNC_PATH = "/api/v1/sync";
    private static final int SYNC_LIMIT = 10;
    private static final int CONFLICT_LIMIT = 60;

    /** バケット保持期間（非アクセス時）。レート窓（1分）より十分長く、OOM は防ぐ。 */
    private static final Duration BUCKET_TTL = Duration.ofMinutes(10);

    /** キャッシュ最大エントリ数。想定外のキー爆発時に LRU で古いものから淘汰する。 */
    private static final long MAX_BUCKETS = 10_000L;

    private final Cache<String, Bucket> syncBuckets = Caffeine.newBuilder()
            .expireAfterAccess(BUCKET_TTL)
            .maximumSize(MAX_BUCKETS)
            .build();

    private final Cache<String, Bucket> conflictBuckets = Caffeine.newBuilder()
            .expireAfterAccess(BUCKET_TTL)
            .maximumSize(MAX_BUCKETS)
            .build();

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

        Cache<String, Bucket> cache;
        String bucketKey;
        int limit;

        if (SYNC_PATH.equals(path) && "POST".equalsIgnoreCase(method)) {
            // POST /api/v1/sync: 1分10回
            cache = syncBuckets;
            bucketKey = "sync-post:" + ip;
            limit = SYNC_LIMIT;
        } else if (path.startsWith(SYNC_PATH + "/conflicts")) {
            // conflicts 系: 1分60回
            cache = conflictBuckets;
            bucketKey = "sync-conflicts:" + ip;
            limit = CONFLICT_LIMIT;
        } else {
            chain.doFilter(request, response);
            return;
        }

        Bucket bucket = resolveBucket(cache, bucketKey, limit);
        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
        } else {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        }
    }

    private Bucket resolveBucket(Cache<String, Bucket> cache, String key, int limit) {
        return cache.get(key, k -> Bucket.builder()
                .addLimit(Bandwidth.simple(limit, Duration.ofMinutes(1)))
                .build());
    }
}
