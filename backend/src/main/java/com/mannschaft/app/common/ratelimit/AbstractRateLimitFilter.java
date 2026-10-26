package com.mannschaft.app.common.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Valkey レートリミットフィルタの共通基底（docs/security/06 §4.3）。
 *
 * <p>各サブクラスは以下のみを実装する:</p>
 * <ul>
 *   <li>{@link #shouldNotFilter(HttpServletRequest)} — 対象エンドポイント判定（早期スキップ）</li>
 *   <li>{@link #resolveRule(HttpServletRequest)} — 適用する (zone, limit, window) の宣言</li>
 * </ul>
 *
 * <p>基底が提供する共通処理:</p>
 * <ul>
 *   <li>制限主体キーの解決 — 認証済みは {@code "u:{userId}"}、未認証は {@code "ip:{ip}"}
 *       （X-Forwarded-For 先頭値を優先。§4.4）</li>
 *   <li>§4.3 標準ヘッダー付与 — {@code X-RateLimit-Limit} / {@code X-RateLimit-Remaining} /
 *       {@code X-RateLimit-Reset}（成功時も付与）</li>
 *   <li>429 レスポンス書き出し — JSON ボディ + {@code Retry-After}（429 時のみ）</li>
 * </ul>
 *
 * <p>{@link ValkeyRateLimiter} は {@link ObjectProvider} 経由で遅延解決する。
 * {@code @WebMvcTest} は {@code jakarta.servlet.Filter} の {@code @Component} を
 * コンテキストに含めるため、必須依存にすると最小スライスのコンテキストロードが
 * 全滅する（過去事故: 横断フィルタの依存追加で 3 系統のテストが破壊）。
 * Bean 不在時は素通しする（本番フルコンテキストでは必ず存在する）。</p>
 */
public abstract class AbstractRateLimitFilter extends OncePerRequestFilter {

    public static final String HEADER_LIMIT = "X-RateLimit-Limit";
    public static final String HEADER_REMAINING = "X-RateLimit-Remaining";
    public static final String HEADER_RESET = "X-RateLimit-Reset";
    public static final String HEADER_RETRY_AFTER = "Retry-After";

    /** 429 レスポンスの JSON ボディ（旧 PublicApiRateLimitFilter と互換）。 */
    private static final String TOO_MANY_REQUESTS_BODY = "{\"error\":\"Too many requests\"}";

    private final ObjectProvider<ValkeyRateLimiter> rateLimiterProvider;

    protected AbstractRateLimitFilter(ObjectProvider<ValkeyRateLimiter> rateLimiterProvider) {
        this.rateLimiterProvider = rateLimiterProvider;
    }

    /**
     * このリクエストに適用するレートリミット規則を返す。
     * 対象外（規則なし）の場合は {@code null} を返すと透過する。
     */
    protected abstract RateLimitRule resolveRule(HttpServletRequest request);

    @Override
    protected final void doFilterInternal(HttpServletRequest request,
                                          HttpServletResponse response,
                                          FilterChain chain) throws ServletException, IOException {
        RateLimitRule rule = resolveRule(request);
        if (rule == null) {
            // shouldNotFilter で弾かれているのが通常だが、念のため透過する
            chain.doFilter(request, response);
            return;
        }

        ValkeyRateLimiter rateLimiter = rateLimiterProvider.getIfAvailable();
        if (rateLimiter == null) {
            // @WebMvcTest 等の最小テストコンテキストで ValkeyRateLimiter Bean が無い場合は素通し。
            // 本番のフルコンテキストでは component scan により必ず存在する。
            chain.doFilter(request, response);
            return;
        }

        String clientKey = resolveClientKey(request);
        RateLimitResult result = rateLimiter.tryConsume(rule.zone(), clientKey, rule.limit(), rule.window());

        // §4.3: 標準ヘッダーは成功時も付与する
        applyRateLimitHeaders(response, result);

        if (result.allowed()) {
            chain.doFilter(request, response);
            onRequestPassed(request, response);
        } else {
            onRateLimitExceeded(request, result);
            writeTooManyRequests(response, result);
        }
    }

    /**
     * 制限主体キーを解決する。認証済みなら {@code "u:{userId}"}、未認証なら {@code "ip:{ip}"}。
     */
    protected String resolveClientKey(HttpServletRequest request) {
        Authentication auth = currentAuthentication();
        if (isAuthenticated(auth)) {
            return "u:" + auth.getName();
        }
        return "ip:" + resolveIp(request);
    }

    /**
     * クライアント IP を解決する（§4.4）。
     *
     * <p>リバースプロキシ経由を考慮し X-Forwarded-For の先頭値を優先する。
     * X-Forwarded-For はスプーフィング可能なため、信頼できるプロキシ配下が前提
     * （18 フィルタ中最も堅牢だった AdPublicEndpointRateLimitFilter の方式を採用）。</p>
     */
    protected static String resolveIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return request.getRemoteAddr();
    }

    /** 現在のリクエストが認証済みか（anonymousUser を除外）。 */
    protected static boolean isAuthenticated() {
        return isAuthenticated(currentAuthentication());
    }

    private static boolean isAuthenticated(Authentication auth) {
        return auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal());
    }

    /** 現在の {@link Authentication}（未認証時は null の場合あり）。 */
    protected static Authentication currentAuthentication() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    /** §4.3 標準ヘッダーを付与する（成功・429 共通）。 */
    protected void applyRateLimitHeaders(HttpServletResponse response, RateLimitResult result) {
        response.setHeader(HEADER_LIMIT, String.valueOf(result.limit()));
        response.setHeader(HEADER_REMAINING, String.valueOf(result.remaining()));
        response.setHeader(HEADER_RESET, String.valueOf(result.resetEpochSeconds()));
    }

    /** 429 Too Many Requests を書き出す（Retry-After + JSON ボディ）。 */
    protected void writeTooManyRequests(HttpServletResponse response, RateLimitResult result)
            throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HEADER_RETRY_AFTER, String.valueOf(result.retryAfterSeconds()));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(TOO_MANY_REQUESTS_BODY);
    }

    /**
     * リクエスト通過後（chain.doFilter 完了後）のフック。
     * メトリクス記録等が必要なサブクラスがオーバーライドする。
     */
    protected void onRequestPassed(HttpServletRequest request, HttpServletResponse response) {
        // デフォルトは何もしない
    }

    /**
     * レート超過時（429 書き出し前）のフック。
     * 監査ログ・超過メトリクス記録等が必要なサブクラスがオーバーライドする。
     */
    protected void onRateLimitExceeded(HttpServletRequest request, RateLimitResult result) {
        // デフォルトは何もしない
    }
}
