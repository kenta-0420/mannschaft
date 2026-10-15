package com.mannschaft.app.repairplan.filter;

import com.mannschaft.app.common.ratelimit.AbstractRateLimitFilter;
import com.mannschaft.app.common.ratelimit.RateLimitResult;
import com.mannschaft.app.common.ratelimit.ValkeyRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 修繕計画シミュレーション用レートリミットフィルタ（F08.8 Phase 2）。
 *
 * <p>{@code POST /api/v1/<scopeType>/<scopeId>/repair-plan/scenarios/simulate}
 * に対して 2 段階のレートリミットを課す:</p>
 * <ul>
 *   <li>ユーザー単位: 20 req/分</li>
 *   <li>スコープ単位（scope_type + scope_id）: 100 req/分</li>
 * </ul>
 *
 * <p>シミュレーション計算は重い処理のため、連続リクエストによるサーバー過負荷を防ぐ。</p>
 *
 * <p><b>二重制限の設計意図</b>: {@link AbstractRateLimitFilter} は単一の
 * {@link com.mannschaft.app.common.ratelimit.RateLimitRule} しか処理できず、
 * かつ {@code doFilterInternal} が {@code final} のためオーバーライド不可。
 * 本フィルタはユーザー単位とスコープ単位の 2 種類の制限を持つため、
 * {@link OncePerRequestFilter} を直接継承し、{@link ValkeyRateLimiter#tryConsume} を
 * user → scope の順に短絡評価で呼ぶ実装にする（user 超過時は scope を消費しない）。
 * 両方が allowed の場合のみリクエストを通過させる。どちらかが超過した場合は 429 を返す。
 * §4.3 標準ヘッダー付与・429 書き出しは {@link AbstractRateLimitFilter} と同一実装で準拠する。</p>
 *
 * <p><b>Valkey 化（第二陣B）</b>: 旧実装の Bucket4j + Caffeine（プロセス内カウント）は
 * ECS 複数タスク構成でタスク数に比例して実効上限が緩むため、
 * {@link ValkeyRateLimiter}（docs/security/06 §4.3）に移行した。</p>
 */
@Component
public class RepairPlanSimulateRateLimitFilter extends OncePerRequestFilter {

    /** ユーザー単位の分あたり許容リクエスト数（旧実装から不変）*/
    private static final int USER_CAPACITY_PER_MINUTE = 20;

    /** スコープ単位の分あたり許容リクエスト数（旧実装から不変）*/
    private static final int SCOPE_CAPACITY_PER_MINUTE = 100;

    private static final Duration WINDOW = Duration.ofMinutes(1);

    /** マッチ対象のパス末尾 */
    private static final String SIMULATE_PATH_SUFFIX = "/repair-plan/scenarios/simulate";

    /** ユーザー単位の Valkey zone */
    private static final String ZONE_USER = "repairplan:simulate:user";

    /** スコープ単位の Valkey zone */
    private static final String ZONE_SCOPE = "repairplan:simulate:scope";

    /** §4.3 標準ヘッダー名（AbstractRateLimitFilter と同じ定数） */
    private static final String HEADER_LIMIT = "X-RateLimit-Limit";
    private static final String HEADER_REMAINING = "X-RateLimit-Remaining";
    private static final String HEADER_RESET = "X-RateLimit-Reset";
    private static final String HEADER_RETRY_AFTER = "Retry-After";

    /** 429 レスポンスの JSON ボディ（AbstractRateLimitFilter と同形式）*/
    private static final String TOO_MANY_REQUESTS_BODY = "{\"error\":\"Too many requests\"}";

    private final ObjectProvider<ValkeyRateLimiter> rateLimiterProvider;

    public RepairPlanSimulateRateLimitFilter(ObjectProvider<ValkeyRateLimiter> rateLimiterProvider) {
        this.rateLimiterProvider = rateLimiterProvider;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getServletPath();
        if (path == null) return true;
        return !path.endsWith(SIMULATE_PATH_SUFFIX);
    }

    /**
     * ユーザーキー → スコープキーの順に {@link ValkeyRateLimiter#tryConsume} を短絡評価で呼び、
     * 両方 allowed の場合のみリクエストを通過させる。どちらかが超過したら 429 を返す。
     *
     * <p>ユーザー制限超過時はスコープ側を消費しない（旧 Bucket4j 実装の
     * {@code userBucket.tryConsume() && scopeBucket.tryConsume()} と同じ短絡意味論）。
     * 無条件に両方消費すると、1 ユーザーの連打中もスコープカウンタ（100/分）が増え続け、
     * 同一スコープの他ユーザーが巻き添えで 429 になるため。</p>
     *
     * §4.3 標準ヘッダー・429 書き出しは {@link AbstractRateLimitFilter} と同一実装で準拠する。
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        ValkeyRateLimiter rateLimiter = rateLimiterProvider.getIfAvailable();
        if (rateLimiter == null) {
            // Bean 不在時（@WebMvcTest 等の最小コンテキスト）は素通し
            chain.doFilter(request, response);
            return;
        }

        String userKey = resolveClientKey(request);

        RateLimitResult userResult = rateLimiter.tryConsume(
                ZONE_USER, userKey, USER_CAPACITY_PER_MINUTE, WINDOW);
        if (!userResult.allowed()) {
            // ユーザー制限超過 — スコープ側は消費せず即 429（短絡評価）
            applyRateLimitHeaders(response, userResult);
            writeTooManyRequests(response, userResult);
            return;
        }

        String scopeKey = resolveScopeKey(request);
        RateLimitResult scopeResult = rateLimiter.tryConsume(
                ZONE_SCOPE, scopeKey, SCOPE_CAPACITY_PER_MINUTE, WINDOW);
        if (!scopeResult.allowed()) {
            applyRateLimitHeaders(response, scopeResult);
            writeTooManyRequests(response, scopeResult);
            return;
        }

        // 両方の制限を通過 — ユーザー制限のヘッダーを付与（より厳しい側）
        applyRateLimitHeaders(response, userResult);
        chain.doFilter(request, response);
    }

    /** §4.3 標準ヘッダーを付与する（AbstractRateLimitFilter と同一実装）。 */
    private static void applyRateLimitHeaders(HttpServletResponse response, RateLimitResult result) {
        response.setHeader(HEADER_LIMIT, String.valueOf(result.limit()));
        response.setHeader(HEADER_REMAINING, String.valueOf(result.remaining()));
        response.setHeader(HEADER_RESET, String.valueOf(result.resetEpochSeconds()));
    }

    /** 429 Too Many Requests を書き出す（AbstractRateLimitFilter と同一実装）。 */
    private static void writeTooManyRequests(HttpServletResponse response, RateLimitResult result)
            throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HEADER_RETRY_AFTER, String.valueOf(result.retryAfterSeconds()));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(TOO_MANY_REQUESTS_BODY);
    }

    /**
     * 制限主体キーを解決する（AbstractRateLimitFilter#resolveClientKey と同一ロジック）。
     * 認証済みなら {@code "u:{userId}"}、未認証なら {@code "ip:{ip}"}。
     */
    private static String resolveClientKey(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            return "u:" + auth.getName();
        }
        return "ip:" + resolveIp(request);
    }

    /**
     * クライアント IP を解決する（X-Forwarded-For 優先・AbstractRateLimitFilter#resolveIp と同一ロジック）。
     */
    private static String resolveIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * スコープキーを URL パスから抽出する。
     * URL 形式: /api/v1/{scopeType}/{scopeId}/repair-plan/scenarios/simulate
     */
    private static String resolveScopeKey(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path == null) return "scope:unknown";
        // /api/v1/ 以降の最初の 2 セグメントを取得
        String[] parts = path.split("/");
        // parts[0]="" parts[1]="api" parts[2]="v1" parts[3]=scopeType parts[4]=scopeId
        if (parts.length >= 5) {
            return "scope:" + parts[3] + ":" + parts[4];
        }
        return "scope:unknown";
    }
}
