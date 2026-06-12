package com.mannschaft.app.memberinfo;

import com.mannschaft.app.common.ratelimit.AbstractRateLimitFilter;
import com.mannschaft.app.common.ratelimit.RateLimitRule;
import com.mannschaft.app.common.ratelimit.ValkeyRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.regex.Pattern;

/**
 * F14.2 チームメンバー情報機能のユーザー別レートリミットフィルタ。
 *
 * <p>{@code PUT /api/v1/teams/{teamId}/member-info/responses/me} に対して 10 req/分 の制限を適用する。</p>
 *
 * <p><b>Valkey 化（第二陣A）</b>: 旧実装の Bucket4j + Caffeine（プロセス内カウント）は
 * ECS 複数タスク構成でタスク数に比例して実効上限が緩むため、
 * {@link ValkeyRateLimiter}（docs/security/06 §4.3）に移行した。</p>
 */
@Component
public class MemberInfoRateLimitFilter extends AbstractRateLimitFilter {

    private static final Pattern UPSERT_RESPONSES_PATTERN =
            Pattern.compile("^/api/v1/teams/[^/]+/member-info/responses/me$");

    private static final int RATE_PER_MINUTE = 10;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    /** Valkey zone 接頭辞。 */
    private static final String ZONE_PREFIX = "memberinfo:";

    public MemberInfoRateLimitFilter(ObjectProvider<ValkeyRateLimiter> rateLimiterProvider) {
        super(rateLimiterProvider);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("PUT".equalsIgnoreCase(request.getMethod())
                && UPSERT_RESPONSES_PATTERN.matcher(request.getServletPath()).matches());
    }

    @Override
    protected RateLimitRule resolveRule(HttpServletRequest request) {
        if ("PUT".equalsIgnoreCase(request.getMethod())
                && UPSERT_RESPONSES_PATTERN.matcher(request.getServletPath()).matches()) {
            return new RateLimitRule(ZONE_PREFIX + "UPSERT_RESPONSES", RATE_PER_MINUTE, WINDOW);
        }
        return null;
    }
}
