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
 * 修繕計画 CSV インポート用レートリミットフィルタ（F08.8 Phase 1）。
 *
 * <p>{@code POST /api/v1/<scopeType>/<scopeId>/repair-plan/items/import-csv}
 * および {@code .../import-csv/confirm} に対し、ユーザー単位で 5 req/分の上限を課す。</p>
 *
 * <p>5MB の CSV アップロード処理が連続して発生するとサーバー負荷が高いため、
 * 人間が手動で操作する現実的なペースを十分上回らない値（人力で 1 分に 5 回は無理）に絞っている。</p>
 *
 * <p>キャッシュ戦略は {@link com.mannschaft.app.actionmemo.ActionMemoRateLimitFilter}
 * と同等：{@code expireAfterAccess=10分} + {@code maximumSize=10000} の Caffeine で OOM を防ぐ。</p>
 */
@Component
public class RepairPlanCsvImportRateLimitFilter extends OncePerRequestFilter {

    /** 分あたりの許容リクエスト数 */
    private static final int CAPACITY_PER_MINUTE = 5;

    /** バケット保持期間（非アクセス時）。レート窓より十分長く、OOM を防ぐ */
    private static final Duration BUCKET_TTL = Duration.ofMinutes(10);

    /** キャッシュ最大エントリ数 */
    private static final long MAX_BUCKETS = 10_000L;

    /** マッチ対象のパス末尾（複数のスコープ階層に対応するため endsWith で判定） */
    private static final String IMPORT_PATH_SUFFIX = "/repair-plan/items/import-csv";

    private static final String CONFIRM_PATH_SUFFIX = "/repair-plan/items/import-csv/confirm";

    private final Cache<String, Bucket> buckets;

    public RepairPlanCsvImportRateLimitFilter() {
        this.buckets = Caffeine.<String, Bucket>newBuilder()
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
        // confirm も import-csv 自身もどちらも /repair-plan/items/import-csv で始まる
        return !(path.endsWith(IMPORT_PATH_SUFFIX) || path.endsWith(CONFIRM_PATH_SUFFIX));
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
