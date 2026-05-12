package com.mannschaft.app.repairplan.filter;

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
 * 修繕計画シミュレーション用レートリミットフィルタ（F08.8 Phase 2）。
 *
 * <p>{@code POST /api/v1/<scopeType>/<scopeId>/repair-plan/scenarios/simulate}
 * に対して 2 段階のレートリミットを課す:</p>
 * <ul>
 *   <li>ユーザー単位: 20 req/分</li>
 *   <li>スコープ単位（scope_type + scope_id）: 100 req/分</li>
 * </ul>
 *
 * <p>シミュレーション計算は重い処理のため、連続リクエストによるサーバー過負荷を防ぐ。
 * キャッシュは {@link RepairPlanCsvImportRateLimitFilter} と同等の Caffeine キャッシュを使用。</p>
 */
@Component
public class RepairPlanSimulateRateLimitFilter extends OncePerRequestFilter {

    /** ユーザー単位の分あたり許容リクエスト数 */
    private static final int USER_CAPACITY_PER_MINUTE = 20;

    /** スコープ単位の分あたり許容リクエスト数 */
    private static final int SCOPE_CAPACITY_PER_MINUTE = 100;

    /** バケット保持期間（非アクセス時）。OOM 防止 */
    private static final Duration BUCKET_TTL = Duration.ofMinutes(10);

    /** キャッシュ最大エントリ数 */
    private static final long MAX_BUCKETS = 10_000L;

    /** マッチ対象のパス末尾 */
    private static final String SIMULATE_PATH_SUFFIX = "/repair-plan/scenarios/simulate";

    private final Cache<String, Bucket> userBuckets;
    private final Cache<String, Bucket> scopeBuckets;

    public RepairPlanSimulateRateLimitFilter() {
        this.userBuckets = Caffeine.<String, Bucket>newBuilder()
                .expireAfterAccess(BUCKET_TTL)
                .maximumSize(MAX_BUCKETS)
                .build();
        this.scopeBuckets = Caffeine.<String, Bucket>newBuilder()
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
        if (path == null) return true;
        return !path.endsWith(SIMULATE_PATH_SUFFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {
        String userKey = resolveUserKey(request);
        String scopeKey = resolveScopeKey(request);

        Bucket userBucket = userBuckets.get(userKey, k -> newUserBucket());
        Bucket scopeBucket = scopeBuckets.get(scopeKey, k -> newScopeBucket());

        if (userBucket.tryConsume(1) && scopeBucket.tryConsume(1)) {
            chain.doFilter(request, response);
        } else {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", "60");
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"errorCode\":\"REPAIR_PLAN_009\",\"message\":\"リクエスト頻度が上限を超えています。しばらく待ってから再試行してください\"}");
        }
    }

    private String resolveUserKey(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getPrincipal())) {
            return "user:" + auth.getName();
        }
        return "ip:" + request.getRemoteAddr();
    }

    /**
     * スコープキーを URL パスから抽出する。
     * URL 形式: /api/v1/{scopeType}/{scopeId}/repair-plan/scenarios/simulate
     */
    private String resolveScopeKey(HttpServletRequest request) {
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

    private Bucket newUserBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.simple(USER_CAPACITY_PER_MINUTE, Duration.ofMinutes(1)))
                .build();
    }

    private Bucket newScopeBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.simple(SCOPE_CAPACITY_PER_MINUTE, Duration.ofMinutes(1)))
                .build();
    }
}
