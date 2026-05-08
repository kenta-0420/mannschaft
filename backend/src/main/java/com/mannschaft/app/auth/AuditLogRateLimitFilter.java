package com.mannschaft.app.auth;

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
 * F10.3 監査ログ API のユーザー別レートリミットフィルタ。
 *
 * <p>以下のエンドポイントに対してレートリミットを適用する:</p>
 * <ul>
 *   <li>{@code GET /api/v1/admin/audit-logs}                  — 60 req/分（SYSTEM_ADMIN 向け）</li>
 *   <li>{@code GET /api/v1/users/me/audit-logs}               — 30 req/分（一般ユーザー向け）</li>
 *   <li>{@code GET /api/v1/teams/{teamId}/audit-logs}         — 30 req/分（チームADMIN向け）</li>
 *   <li>{@code GET /api/v1/organizations/{orgId}/audit-logs}  — 30 req/分（組織ADMIN向け）</li>
 * </ul>
 *
 * <p><b>キャッシュ戦略</b>: Caffeine の {@code expireAfterAccess=10分} + {@code maximumSize=10000}。
 * {@link com.mannschaft.app.memberinfo.MemberInfoRateLimitFilter} と同一パターン。</p>
 */
@Component
public class AuditLogRateLimitFilter extends OncePerRequestFilter {

    /** エンドポイント別の設定 */
    private enum Endpoint {
        ADMIN_AUDIT_LOGS("/api/v1/admin/audit-logs", "GET", 60),
        MY_AUDIT_LOGS("/api/v1/users/me/audit-logs", "GET", 30),
        TEAM_AUDIT_LOGS("/api/v1/teams/*/audit-logs", "GET", 30),
        ORGANIZATION_AUDIT_LOGS("/api/v1/organizations/*/audit-logs", "GET", 30);

        final String path;
        final String method;
        final int capacityPerMinute;

        Endpoint(String path, String method, int capacityPerMinute) {
            this.path = path;
            this.method = method;
            this.capacityPerMinute = capacityPerMinute;
        }

        boolean matches(HttpServletRequest request) {
            if (!this.method.equalsIgnoreCase(request.getMethod())) return false;
            String servletPath = request.getServletPath();
            if (!this.path.contains("*")) {
                return this.path.equals(servletPath);
            }
            // ワイルドカード: prefix*suffix 形式のみサポート
            int starIdx = this.path.indexOf('*');
            String prefix = this.path.substring(0, starIdx);
            String suffix = this.path.substring(starIdx + 1);
            return servletPath.startsWith(prefix) && servletPath.endsWith(suffix)
                    && servletPath.length() > prefix.length() + suffix.length();
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

    public AuditLogRateLimitFilter() {
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
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"message\": \"リクエストが多すぎます。しばらく経ってから再試行してください。\"}");
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
     * 認証済みなら "u:{userId}" を、未認証なら "ip:{remoteAddr}" をキーにする。
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
