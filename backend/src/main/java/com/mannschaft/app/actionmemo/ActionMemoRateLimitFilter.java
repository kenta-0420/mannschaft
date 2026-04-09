package com.mannschaft.app.actionmemo;

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
import java.util.concurrent.ConcurrentHashMap;

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
 * <p><b>実装</b>: {@code ErrorReportRateLimitFilter} を雛形に ConcurrentHashMap ベースの
 * インメモリ実装。単一インスタンス環境で十分機能し、将来的に Bucket4j-Redis に差し替え可能。</p>
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

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

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
        String bucketKey = endpoint.name() + ":" + userKey;
        Bucket bucket = buckets.computeIfAbsent(bucketKey, k -> newBucket(endpoint.capacityPerMinute));

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
