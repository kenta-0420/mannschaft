package com.mannschaft.app.dashboard;

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

/**
 * F22.1: 横スワイプ・ダッシュボード scope-tabs のレートリミットフィルタ。
 *
 * <p>設計書 02_api_design.md §5 に従い、{@code PUT /api/v1/dashboard/scope-tabs/order}
 * に対してユーザー単位 30 req/分のレートリミットを適用する（並べ替え確定の連打防止）。
 * GET 側は読み取り・ページ送りの連打を許容するため対象外（§5）。</p>
 *
 * <p>JWT 認証後に確定した SecurityContext から userId を解決するため、SecurityConfig では
 * {@code addFilterAfter(..., JwtAuthenticationFilter.class)} で登録する
 * （{@link com.mannschaft.app.actionmemo.ActionMemoRateLimitFilter} と同方針）。</p>
 *
 * <p>キャッシュ戦略: Caffeine の {@code expireAfterAccess=10分} + {@code maximumSize=10000}。
 * 非アクティブなバケットは自動削除され、OOM を防ぐ。</p>
 */
@Component
public class DashboardScopeTabRateLimitFilter extends OncePerRequestFilter {

    private static final String PATH = "/api/v1/dashboard/scope-tabs/order";
    private static final String METHOD = "PUT";
    private static final int CAPACITY_PER_MINUTE = 30;

    private static final Duration BUCKET_TTL = Duration.ofMinutes(10);
    private static final long MAX_BUCKETS = 10_000L;

    private final Cache<String, Bucket> buckets = Caffeine.<String, Bucket>newBuilder()
            .expireAfterAccess(BUCKET_TTL)
            .maximumSize(MAX_BUCKETS)
            .build();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !(PATH.equals(request.getServletPath()) && METHOD.equalsIgnoreCase(request.getMethod()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String userKey = resolveUserKey(request);
        Bucket bucket = buckets.get(userKey, k -> newBucket());

        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
        } else {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", "60");
        }
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

    private Bucket newBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.simple(CAPACITY_PER_MINUTE, Duration.ofMinutes(1)))
                .build();
    }
}
