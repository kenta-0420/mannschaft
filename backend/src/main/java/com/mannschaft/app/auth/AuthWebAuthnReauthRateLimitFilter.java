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

/**
 * F18 提示モード追加保護用の WebAuthn 再認証エンドポイント専用レートリミットフィルタ。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §9.6
 *
 * <p>対象パス（各 10 req/分）:
 * <ul>
 *   <li>{@code POST /api/v1/auth/webauthn/reauthenticate-begin}</li>
 *   <li>{@code POST /api/v1/auth/webauthn/reauthenticate-complete}</li>
 * </ul>
 *
 * <p>キャッシュ戦略: Caffeine の {@code expireAfterAccess=10 分} + {@code maximumSize=10000}。
 * 既存の {@link AuditLogRateLimitFilter} と同パターン。
 *
 * <p>※ ログイン用 {@code /login/begin} / {@code /login/complete} には影響しない。
 * 提示モード追加保護に限った独立フィルタ。
 */
@Component
public class AuthWebAuthnReauthRateLimitFilter extends OncePerRequestFilter {

    private static final String REAUTH_BEGIN_PATH = "/api/v1/auth/webauthn/reauthenticate-begin";
    private static final String REAUTH_COMPLETE_PATH = "/api/v1/auth/webauthn/reauthenticate-complete";
    private static final int RATE_PER_MINUTE = 10;

    private static final Duration BUCKET_TTL = Duration.ofMinutes(10);
    private static final long MAX_BUCKETS = 10_000L;

    private final Cache<String, Bucket> beginBuckets;
    private final Cache<String, Bucket> completeBuckets;

    public AuthWebAuthnReauthRateLimitFilter() {
        this.beginBuckets = newCache();
        this.completeBuckets = newCache();
    }

    private static Cache<String, Bucket> newCache() {
        return Caffeine.<String, Bucket>newBuilder()
                .expireAfterAccess(BUCKET_TTL)
                .maximumSize(MAX_BUCKETS)
                .build();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getServletPath();
        return !(REAUTH_BEGIN_PATH.equals(path) || REAUTH_COMPLETE_PATH.equals(path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {
        String path = request.getServletPath();
        Cache<String, Bucket> cache;
        if (REAUTH_BEGIN_PATH.equals(path)) {
            cache = beginBuckets;
        } else if (REAUTH_COMPLETE_PATH.equals(path)) {
            cache = completeBuckets;
        } else {
            chain.doFilter(request, response);
            return;
        }

        String userKey = resolveUserKey(request);
        Bucket bucket = cache.get(userKey, k -> newBucket());

        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
        } else {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", "60");
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(
                    "{\"message\": \"再認証の試行が多すぎます。しばらく経ってから再試行してください。\"}");
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

    private Bucket newBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.simple(RATE_PER_MINUTE, Duration.ofMinutes(1)))
                .build();
    }
}
