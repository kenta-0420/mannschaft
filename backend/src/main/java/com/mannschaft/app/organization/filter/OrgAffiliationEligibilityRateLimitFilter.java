package com.mannschaft.app.organization.filter;

import com.mannschaft.app.common.ratelimit.AbstractRateLimitFilter;
import com.mannschaft.app.common.ratelimit.RateLimitRule;
import com.mannschaft.app.common.ratelimit.ValkeyRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * F01.2.1 §10.10: 申請ボタン判定 {@code GET /api/v1/me/org-affiliation-eligibility} のレートリミット
 * （60件/分/ユーザー。超過で 429 + {@code Retry-After}）。
 *
 * <p>判定 API は常に 200 で理由を区別しないが、slug を総当たりして「受付中の組織」を探る用途に連打されうるため
 * 回数で絞る。制限主体は認証済みユーザー（{@code u:{userId}}）。共通基盤 {@link AbstractRateLimitFilter} に従う。</p>
 */
@Component
public class OrgAffiliationEligibilityRateLimitFilter extends AbstractRateLimitFilter {

    /** §10.10: 1分あたりの上限。60回目までは通り、61回目が 429 になる。 */
    public static final int LIMIT_PER_MINUTE = 60;

    static final String PATH = "/api/v1/me/org-affiliation-eligibility";

    private static final RateLimitRule RULE =
            new RateLimitRule("org-affiliation-eligibility", LIMIT_PER_MINUTE, Duration.ofMinutes(1));

    public OrgAffiliationEligibilityRateLimitFilter(ObjectProvider<ValkeyRateLimiter> rateLimiterProvider) {
        super(rateLimiterProvider);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("GET".equalsIgnoreCase(request.getMethod()) && PATH.equals(request.getServletPath()));
    }

    @Override
    protected RateLimitRule resolveRule(HttpServletRequest request) {
        return shouldNotFilter(request) ? null : RULE;
    }
}
