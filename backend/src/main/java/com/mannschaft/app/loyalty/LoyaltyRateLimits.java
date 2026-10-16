package com.mannschaft.app.loyalty;

import com.mannschaft.app.common.ratelimit.ValkeyRateLimiter;
import org.springframework.stereotype.Component;

/** 設計§10のpreview/書込actor別とTEAM合計の分窓。 */
@Component
public class LoyaltyRateLimits {
    public LoyaltyRateLimits(ValkeyRateLimiter limiter) {}
    public void preview(Long actor, Long team) {}
    public void write(Long actor, Long team) {}
}
