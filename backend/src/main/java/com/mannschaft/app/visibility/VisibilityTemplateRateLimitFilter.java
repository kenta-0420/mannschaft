package com.mannschaft.app.visibility;

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
 * F01.7 カスタム公開範囲テンプレートのユーザー別レートリミットフィルタ。
 *
 * <p>設計書 §5 に従い、以下の5エンドポイントに対してユーザー単位のレートリミットを適用する:</p>
 * <ul>
 *   <li>{@code POST  /api/v1/visibility-templates} - 10 req/時間</li>
 *   <li>{@code PUT   /api/v1/visibility-templates/{id}} - 30 req/時間</li>
 *   <li>{@code DELETE /api/v1/visibility-templates/{id}} - 30 req/時間</li>
 *   <li>{@code POST  /api/v1/visibility-templates/{id}/evaluate} - 100 req/時間</li>
 *   <li>{@code GET   /api/v1/visibility-templates/{id}/resolved-members} - 20 req/時間</li>
 * </ul>
 *
 * <p><b>設計意図</b>: テンプレート作成・更新・削除は操作の重み（DB書き込み）に対して上限を設定。
 * evaluate は閲覧時に頻繁に呼ばれるため余裕を持った上限を設定。
 * ボット・スクリプト・連打バグの防御のみを目的とする。</p>
 *
 * <p><b>キャッシュ戦略</b>: Caffeine の {@code expireAfterAccess=70分} + {@code maximumSize=10000}。
 * レートリミット窓（1時間）より少し長い TTL を設定して、
 * 非アクティブなキーはメモリから自動削除される。</p>
 *
 * <p>エンドポイントごとに Cache を分離しているのは、片方のトラフィックが
 * もう片方のエントリを LRU で押し出すのを避けるため
 * ({@link com.mannschaft.app.actionmemo.ActionMemoRateLimitFilter} と同じ方針)。</p>
 */
@Component
public class VisibilityTemplateRateLimitFilter extends OncePerRequestFilter {

    private static final String BASE_PATH = "/api/v1/visibility-templates";

    /** エンドポイント別の設定 */
    private enum Endpoint {
        CREATE_TEMPLATE(BASE_PATH, "POST", 10),
        UPDATE_TEMPLATE(BASE_PATH + "/*", "PUT", 30),
        DELETE_TEMPLATE(BASE_PATH + "/*", "DELETE", 30),
        EVALUATE(BASE_PATH + "/*/evaluate", "POST", 100),
        RESOLVED_MEMBERS(BASE_PATH + "/*/resolved-members", "GET", 20);

        final String pathPattern;
        final String method;
        final int capacityPerHour;

        Endpoint(String pathPattern, String method, int capacityPerHour) {
            this.pathPattern = pathPattern;
            this.method = method;
            this.capacityPerHour = capacityPerHour;
        }

        boolean matches(HttpServletRequest request) {
            return this.method.equalsIgnoreCase(request.getMethod())
                    && matchesPath(request.getRequestURI());
        }

        private boolean matchesPath(String uri) {
            // 末尾スラッシュを除去して比較
            String normalizedUri = uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;

            if (!pathPattern.contains("*")) {
                // 完全一致
                return pathPattern.equals(normalizedUri);
            }

            // ワイルドカードパターンマッチング
            String[] patternParts = pathPattern.split("/\\*/");
            if (patternParts.length == 1) {
                // 末尾がワイルドカード: BASE_PATH/* の形式
                String prefix = pathPattern.replace("/*", "");
                if (!normalizedUri.startsWith(prefix + "/")) {
                    return false;
                }
                // BASE_PATH/{id} の形式：さらにサブパスがないことを確認
                String remainder = normalizedUri.substring(prefix.length() + 1);
                return !remainder.isEmpty() && !remainder.contains("/");
            }

            // 中間にワイルドカード: BASE_PATH/*/evaluate または BASE_PATH/*/resolved-members
            String prefix = patternParts[0];
            String suffix = patternParts[1];
            if (!normalizedUri.startsWith(prefix + "/")) {
                return false;
            }
            String afterPrefix = normalizedUri.substring(prefix.length() + 1);
            int slashIndex = afterPrefix.indexOf('/');
            if (slashIndex < 0) {
                return false;
            }
            String afterId = afterPrefix.substring(slashIndex + 1);
            return suffix.equals(afterId);
        }
    }

    /** バケット保持期間（非アクセス時）。レート窓（1時間）より少し長く設定。 */
    private static final Duration BUCKET_TTL = Duration.ofMinutes(70);

    /** キャッシュ最大エントリ数。想定外のキー爆発時に LRU で古いものから淘汰する。 */
    private static final long MAX_BUCKETS = 10_000L;

    /**
     * エンドポイント別のバケットキャッシュ。エンドポイント間で LRU 淘汰が干渉しないよう
     * それぞれ独立した Cache として保持する。
     */
    private final Map<Endpoint, Cache<String, Bucket>> bucketsByEndpoint;

    public VisibilityTemplateRateLimitFilter() {
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
        if (userKey == null) {
            // 未認証の場合はレート制限をスキップ（セキュリティフィルタで弾かれる）
            chain.doFilter(request, response);
            return;
        }

        Cache<String, Bucket> cache = bucketsByEndpoint.get(endpoint);
        Bucket bucket = cache.get(userKey, k -> newBucket(endpoint.capacityPerHour));

        long remainingTokens = bucket.getAvailableTokens();
        if (bucket.tryConsume(1)) {
            response.setHeader("X-RateLimit-Remaining", String.valueOf(remainingTokens - 1));
            chain.doFilter(request, response);
        } else {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("X-RateLimit-Remaining", "0");
            response.setHeader("Retry-After", "3600");
        }
    }

    private Endpoint resolveEndpoint(HttpServletRequest request) {
        // 優先度順にマッチング（より具体的なパターンを先に評価）
        // evaluate と resolved-members は PUT/DELETE より先に判定する必要がある
        for (Endpoint ep : new Endpoint[]{
                Endpoint.EVALUATE,
                Endpoint.RESOLVED_MEMBERS,
                Endpoint.CREATE_TEMPLATE,
                Endpoint.UPDATE_TEMPLATE,
                Endpoint.DELETE_TEMPLATE
        }) {
            if (ep.matches(request)) {
                return ep;
            }
        }
        return null;
    }

    /**
     * 認証済みなら userId を返す。未認証なら null を返す。
     */
    private String resolveUserKey(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getPrincipal())) {
            return "u:" + auth.getName();
        }
        return null;
    }

    private Bucket newBucket(int capacityPerHour) {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(capacityPerHour)
                        .refillGreedy(capacityPerHour, Duration.ofHours(1))
                        .build())
                .build();
    }
}
