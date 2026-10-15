package com.mannschaft.app.event;

import com.mannschaft.app.common.ratelimit.AbstractRateLimitFilter;
import com.mannschaft.app.common.ratelimit.RateLimitRule;
import com.mannschaft.app.common.ratelimit.ValkeyRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

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
 * <p>スケジュール側 {@link com.mannschaft.app.schedule.ScheduleDelegationRateLimitFilter} と同型。</p>
 *
 * <p>登録方式: {@link com.mannschaft.app.config.SecurityConfig#eventDelegationRateLimitFilterRegistration}
 * で @Component 自動登録を無効化し、SecurityConfig の {@code addFilterAfter} 経由のみで動作させる。</p>
 *
 * <p><b>Valkey 化（第二陣B）</b>: 旧実装の Bucket4j + Caffeine（プロセス内カウント）は
 * ECS 複数タスク構成でタスク数に比例して実効上限が緩むため、
 * {@link ValkeyRateLimiter}（docs/security/06 §4.3）に移行した。
 * カウント・§4.3 標準ヘッダー・429 応答は {@link AbstractRateLimitFilter} が担う。</p>
 */
@Component
public class EventDelegationRateLimitFilter extends AbstractRateLimitFilter {

    /** 対象: POST /api/v1/events/{eventId}/delegations（末尾 /me や /{id}/checkin を含まない）。 */
    private static final Pattern TARGET_PATH =
            Pattern.compile("^/api/v1/events/\\d+/delegations/?$");

    /** 1 分間あたりの上限回数（§6-6・旧実装から不変）。 */
    private static final int CAPACITY_PER_MINUTE = 10;

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private static final String ZONE = "event:delegation";

    public EventDelegationRateLimitFilter(ObjectProvider<ValkeyRateLimiter> rateLimiterProvider) {
        super(rateLimiterProvider);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !isTarget(request);
    }

    @Override
    protected RateLimitRule resolveRule(HttpServletRequest request) {
        if (!isTarget(request)) {
            return null;
        }
        return new RateLimitRule(ZONE, CAPACITY_PER_MINUTE, WINDOW);
    }

    private boolean isTarget(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod())
                && TARGET_PATH.matcher(request.getServletPath()).matches();
    }
}
