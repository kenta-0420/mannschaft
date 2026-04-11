package com.mannschaft.app.actionmemo;

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
import java.util.EnumMap;
import java.util.Map;

/**
 * F02.5 行動メモ機能のユーザー別レートリミットフィルタ。
 *
 * <p>設計書 §6 に従い、以下の4エンドポイントに対してユーザー単位のレートリミットを適用する:</p>
 * <ul>
 *   <li>{@code POST   /api/v1/action-memos} — 60 req/分</li>
 *   <li>{@code POST   /api/v1/action-memos/publish-daily} — 5 req/分（Phase 2 で使用）</li>
 *   <li>{@code POST   /api/v1/action-memo-tags} — 20 req/分（Phase 4 で使用）</li>
 *   <li>{@code PATCH  /api/v1/action-memo-settings} — 10 req/分</li>
 * </ul>
 *
 * <p><b>設計意図</b>: いずれも人間が手動で打ち込む現実的な最大値の数倍に設定。
 * ADHD ユーザーの「思いついた瞬間に書く」摩擦ゼロ原則に矛盾しないよう、
 * ボット・スクリプト・連打バグの防御のみを目的とする。</p>
 *
 * <p><b>キャッシュ戦略</b>: Caffeine の {@code expireAfterAccess=10分} + {@code maximumSize=10000}。
 * 旧実装の {@code ConcurrentHashMap} は Eviction ポリシーが無く、ユーザー/IP ごとにバケットが
 * 永続化されるため長期稼働で OOM を引き起こす懸念があった。
 * {@link com.mannschaft.app.sync.SyncRateLimitFilter} と同一パターンで統一している。</p>
 * <ul>
 *   <li>10 分間アクセスが無いバケットは自動削除される（非アクティブなキーはメモリから消える）</li>
 *   <li>最大 10000 エントリを超えると LRU で淘汰される（想定外のキー爆発を防ぐ）</li>
 *   <li>レートリミット窓は Bucket4j の Bandwidth（1分）側が管理するため、
 *       10 分の TTL はメモリ保持期間に関する上限であって仕様に影響しない</li>
 * </ul>
 *
 * <p>エンドポイントごとに Cache を分離しているのは、片方のトラフィックが
 * もう片方のエントリを LRU で押し出すのを避けるため
 * （{@link com.mannschaft.app.sync.SyncRateLimitFilter} と同じ方針）。</p>
 */
@Component
public class ActionMemoRateLimitFilter extends OncePerRequestFilter {

    /** エンドポイント別の設定 */
    private enum Endpoint {
        CREATE_MEMO("/api/v1/action-memos", "POST", 60),
        PUBLISH_DAILY("/api/v1/action-memos/publish-daily", "POST", 5),
        CREATE_TAG("/api/v1/action-memo-tags", "POST", 20),
        UPDATE_SETTINGS("/api/v1/action-memo-settings", "PATCH", 10);

        final String path;
        final String method;
        final int capacityPerMinute;

        Endpoint(String path, String method, int capacityPerMinute) {
            this.path = path;
            this.method = method;
            this.capacityPerMinute = capacityPerMinute;
        }

        boolean matches(HttpServletRequest request) {
            return this.path.equals(request.getServletPath())
                    && this.method.equalsIgnoreCase(request.getMethod());
        }
    }

    /** バケット保持期間（非アクセス時）。レート窓（1分）より十分長く、OOM は防ぐ。 */
    private static final Duration BUCKET_TTL = Duration.ofMinutes(10);

    /** キャッシュ最大エントリ数。想定外のキー爆発時に LRU で古いものから淘汰する。 */
    private static final long MAX_BUCKETS = 10_000L;

    /**
     * エンドポイント別のバケットキャッシュ。エンドポイント間で LRU 淘汰が干渉しないよう
     * それぞれ独立した Cache として保持する。
     */
    private final Map<Endpoint, Cache<String, Bucket>> bucketsByEndpoint;

    public ActionMemoRateLimitFilter() {
        this.bucketsByEndpoint = new EnumMap<>(Endpoint.class);
        for (Endpoint ep : Endpoint.values()) {
            this.bucketsByEndpoint.put(ep, Caffeine.<String, Bucket>newBuilder()
                    .expireAfterAccess(BUCKET_TTL)
                    .maximumSize(MAX_BUCKETS)
                    .build());
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        for (Endpoint ep : Endpoint.values()) {
            if (ep.matches(request)) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {
        Endpoint endpoint = resolveEndpoint(request);
        if (endpoint == null) {
            chain.doFilter(request, response);
            return;
        }

        String userKey = resolveUserKey(request);
        Cache<String, Bucket> cache = bucketsByEndpoint.get(endpoint);
        Bucket bucket = cache.get(userKey, k -> newBucket(endpoint.capacityPerMinute));

        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
        } else {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", "60");
        }
    }

    private Endpoint resolveEndpoint(HttpServletRequest request) {
        for (Endpoint ep : Endpoint.values()) {
            if (ep.matches(request)) {
                return ep;
            }
        }
        return null;
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

    private Bucket newBucket(int capacityPerMinute) {
        return Bucket.builder()
                .addLimit(Bandwidth.simple(capacityPerMinute, Duration.ofMinutes(1)))
                .build();
    }
}
