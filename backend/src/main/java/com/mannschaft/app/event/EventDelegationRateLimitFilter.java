package com.mannschaft.app.event;

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
import java.util.regex.Pattern;

/**
 * F03.10 イベント代理指定のレートリミットフィルタ（設計書 §6）。
 *
 * <p>{@code POST /api/v1/events/{eventId}/delegations} をユーザー単位で 1 分間に 10 回に制限する。
 * 不正なメンバー ID を試行する攻撃（§6-6）への対策。超過時は 429 Too Many Requests を返す。
 * 代理チェックイン（{@code .../checkin}）はパスがさらに深いため、本フィルタの対象外（末尾が
 * {@code /delegations} で終わる POST のみ対象）。</p>
 *
 * <p>手本: {@link com.mannschaft.app.actionmemo.ActionMemoRateLimitFilter}。スケジュール側
 * {@link com.mannschaft.app.schedule.ScheduleDelegationRateLimitFilter} と同型。</p>
 */
@Component
public class EventDelegationRateLimitFilter extends OncePerRequestFilter {

    /** 対象: POST /api/v1/events/{eventId}/delegations（末尾 /me や /{id}/checkin を含まない）。 */
    private static final Pattern TARGET_PATH =
            Pattern.compile("^/api/v1/events/\\d+/delegations/?$");

    /** 1 分間あたりの上限回数（§6-6）。 */
    private static final int CAPACITY_PER_MINUTE = 10;

    /** バケット保持期間（非アクセス時）。レート窓（1分）より十分長く、OOM は防ぐ。 */
    private static final Duration BUCKET_TTL = Duration.ofMinutes(10);

    /** キャッシュ最大エントリ数。想定外のキー爆発時に LRU で古いものから淘汰する。 */
    private static final long MAX_BUCKETS = 10_000L;

    private final Cache<String, Bucket> buckets = Caffeine.<String, Bucket>newBuilder()
            .expireAfterAccess(BUCKET_TTL)
            .maximumSize(MAX_BUCKETS)
            .build();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !isTarget(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!isTarget(request)) {
            chain.doFilter(request, response);
            return;
        }

        String userKey = resolveUserKey(request);
        Bucket bucket = buckets.get(userKey, k -> newBucket());

        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
        } else {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", "60");
        }
    }

    private boolean isTarget(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod())
                && TARGET_PATH.matcher(request.getServletPath()).matches();
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
