package com.mannschaft.app.organization.teamgroup.filter;

import com.mannschaft.app.common.ratelimit.AbstractRateLimitFilter;
import com.mannschaft.app.common.ratelimit.RateLimitRule;
import com.mannschaft.app.common.ratelimit.ValkeyRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.regex.Pattern;

/**
 * 一括グループ割当のユーザー別レートリミットフィルタ（F01.2.1 §10.10）。
 *
 * <p>{@code PUT /api/v1/organizations/{slug}/team-group-assignments} を<b>ユーザー単位</b>で 1 分に 20 件に制限する。
 * 一括割当は 1 回で最大 500 チームの加盟行と監査ログを書くため、連打を抑える。
 * 単体割当（{@code PUT .../teams/{teamSlug}/team-group}）と参照系は対象外。</p>
 *
 * <p>認証済みのリクエストだけが対象（未認証は認証フィルタが 401 にする）。キーは基底の {@code "u:{userId}"} 形式を使う。
 * 上限を超えると 429 と {@code Retry-After}。カウント・標準ヘッダー・429 応答は {@link AbstractRateLimitFilter} が担う。
 * 同ドメインの流儀は {@code TeamOrgApplicationRateLimitFilter}（{@code @Component}・{@link ObjectProvider} 経由の遅延解決）に揃える。</p>
 */
@Component
public class OrgTeamGroupAssignmentRateLimitFilter extends AbstractRateLimitFilter {

    /** 対象: PUT /api/v1/organizations/{slug}/team-group-assignments（1 階層だけを捕捉する）。 */
    private static final Pattern TARGET_PATH =
            Pattern.compile("^/api/v1/organizations/[^/]+/team-group-assignments/?$");

    /** 1 分あたりの上限（§10.10）。 */
    static final int LIMIT_PER_MINUTE = 20;

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private static final String ZONE = "org-team-group:bulk-assign";

    public OrgTeamGroupAssignmentRateLimitFilter(ObjectProvider<ValkeyRateLimiter> rateLimiterProvider) {
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
        return new RateLimitRule(ZONE, LIMIT_PER_MINUTE, WINDOW);
    }

    private boolean isTarget(HttpServletRequest request) {
        return "PUT".equalsIgnoreCase(request.getMethod())
                && TARGET_PATH.matcher(request.getServletPath()).matches();
    }
}
