package com.mannschaft.app.loyalty;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.ratelimit.ValkeyRateLimiter;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * 設計§10のpreview/書込actor別とTEAM合計の分窓。
 * 成功receipt照合後の新規操作にだけ適用し、Valkey障害時もDBの一意制約・ロックは省略しない。
 */
@Component
public class LoyaltyRateLimits {
    private static final Duration WINDOW = Duration.ofMinutes(1);
    private final ValkeyRateLimiter limiter;

    public LoyaltyRateLimits(ValkeyRateLimiter limiter) { this.limiter = limiter; }

    public void preview(Long actor, Long team) { consume("loyalty-preview", actor, team); }
    public void write(Long actor, Long team) { consume("loyalty-write", actor, team); }

    private void consume(String zone, Long actor, Long team) {
        if (!limiter.tryConsume(zone, "u:" + actor, 60, WINDOW).allowed()
                || !limiter.tryConsume("loyalty-team", "t:" + team, 1000, WINDOW).allowed()) {
            throw new BusinessException(LoyaltyErrorCode.LOYALTY_012);
        }
    }
}
