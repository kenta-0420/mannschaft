package com.mannschaft.app.recruitment.filter;

import com.mannschaft.app.common.ratelimit.AbstractRateLimitFilter;
import com.mannschaft.app.common.ratelimit.RateLimitRule;
import com.mannschaft.app.common.ratelimit.ValkeyRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 個人札の作成と通報の、認証済み利用者単位レート制限。 */
@Component
public class MarketMutationRateLimitFilter extends AbstractRateLimitFilter {

    private static final String PERSONAL_LISTING_PATH = "/api/v1/me/market/listings";
    private static final String REPORT_PATH = "/api/v1/reports";

    public MarketMutationRateLimitFilter(ObjectProvider<ValkeyRateLimiter> rateLimiterProvider) {
        super(rateLimiterProvider);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return resolveRule(request) == null;
    }

    @Override
    protected RateLimitRule resolveRule(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return null;
        }
        if (PERSONAL_LISTING_PATH.equals(request.getServletPath())) {
            return new RateLimitRule("market:personal-listing-create", 30, Duration.ofHours(1));
        }
        if (REPORT_PATH.equals(request.getServletPath())) {
            return new RateLimitRule("moderation:report-create", 10, Duration.ofMinutes(1));
        }
        return null;
    }
}
