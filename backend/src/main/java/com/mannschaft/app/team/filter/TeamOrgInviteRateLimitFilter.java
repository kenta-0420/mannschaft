package com.mannschaft.app.team.filter;

import com.mannschaft.app.common.ratelimit.AbstractRateLimitFilter;
import com.mannschaft.app.common.ratelimit.RateLimitRule;
import com.mannschaft.app.common.ratelimit.ValkeyRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.regex.Pattern;

/**
 * 組織からチームへの加盟招待のユーザー別レートリミットフィルタ（F01.2.1 §10.10）。
 *
 * <p>{@code POST /api/v1/organizations/{slug}/team-invites} を<b>ユーザー単位</b>で 1 時間に 30 件に制限する。
 * 招待・取消の繰り返しで相手チームへ通知が連打されるのを抑えるための上限である
 * （取消後 24 時間の再招待停止とは別の、時間あたりの回数制限）。</p>
 *
 * <p>認証済みのリクエストだけが対象（未認証は認証フィルタが 401 にする）。キーは基底の {@code "u:{userId}"} 形式を使う。
 * 上限を超えると 429 と {@code Retry-After}。流儀は {@link TeamOrgApplicationRateLimitFilter} に揃える。</p>
 */
@Component
public class TeamOrgInviteRateLimitFilter extends AbstractRateLimitFilter {

    /** 対象: POST /api/v1/organizations/{slug}/team-invites（1 階層だけを捕捉する。取消の DELETE は対象外）。 */
    private static final Pattern TARGET_PATH =
            Pattern.compile("^/api/v1/organizations/[^/]+/team-invites/?$");

    /** 1 時間あたりの上限（§10.10）。 */
    static final int LIMIT_PER_HOUR = 30;

    private static final Duration WINDOW = Duration.ofHours(1);

    private static final String ZONE = "team-org-affiliation:invite";

    public TeamOrgInviteRateLimitFilter(ObjectProvider<ValkeyRateLimiter> rateLimiterProvider) {
        super(rateLimiterProvider);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !isTarget(request) || !isAuthenticated();
    }

    @Override
    protected RateLimitRule resolveRule(HttpServletRequest request) {
        if (!isTarget(request)) {
            return null;
        }
        return new RateLimitRule(ZONE, LIMIT_PER_HOUR, WINDOW);
    }

    private boolean isTarget(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod())
                && TARGET_PATH.matcher(request.getServletPath()).matches();
    }
}
