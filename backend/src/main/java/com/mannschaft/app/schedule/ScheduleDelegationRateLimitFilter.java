package com.mannschaft.app.schedule;

import com.mannschaft.app.common.ratelimit.AbstractRateLimitFilter;
import com.mannschaft.app.common.ratelimit.RateLimitRule;
import com.mannschaft.app.common.ratelimit.ValkeyRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.regex.Pattern;

/**
 * F03.10 スケジュール代理指定のレートリミットフィルタ（設計書 §6）。
 *
 * <p>{@code POST /api/v1/schedules/{scheduleId}/delegations} をユーザー単位で 1 分間に 10 回に制限する。
 * 不正なメンバー ID を試行する攻撃（§6-6）への対策。超過時は 429 Too Many Requests を返す。</p>
 *
 * <p>対象パスがパス変数（{@code {scheduleId}}）を含むため、固定文字列比較ではなく
 * 正規表現でマッチする。</p>
 *
 * <p>登録方式: {@link com.mannschaft.app.config.SecurityConfig#scheduleDelegationRateLimitFilterRegistration}
 * で @Component 自動登録を無効化し、SecurityConfig の {@code addFilterAfter} 経由のみで動作させる。
 * JWT 認証後に動かし、確定した SecurityContext から userId を解決できるようにする。</p>
 *
 * <p><b>Valkey 化（第二陣B）</b>: 旧実装の Bucket4j + Caffeine（プロセス内カウント）は
 * ECS 複数タスク構成でタスク数に比例して実効上限が緩むため、
 * {@link ValkeyRateLimiter}（docs/security/06 §4.3）に移行した。
 * カウント・§4.3 標準ヘッダー・429 応答は {@link AbstractRateLimitFilter} が担う。</p>
 */
@Component
public class ScheduleDelegationRateLimitFilter extends AbstractRateLimitFilter {

    /** 対象: POST /api/v1/schedules/{scheduleId}/delegations（末尾 /me を含まない）。 */
    private static final Pattern TARGET_PATH =
            Pattern.compile("^/api/v1/schedules/\\d+/delegations/?$");

    /** 1 分間あたりの上限回数（§6-6・旧実装から不変）。 */
    private static final int CAPACITY_PER_MINUTE = 10;

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private static final String ZONE = "schedule:delegation";

    public ScheduleDelegationRateLimitFilter(ObjectProvider<ValkeyRateLimiter> rateLimiterProvider) {
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
