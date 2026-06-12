package com.mannschaft.app.social.announcement;

import com.mannschaft.app.common.ratelimit.AbstractRateLimitFilter;
import com.mannschaft.app.common.ratelimit.RateLimitRule;
import com.mannschaft.app.common.ratelimit.ValkeyRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.regex.Pattern;

/**
 * 告知ウィザード broadcast エンドポイントのユーザー別レートリミットフィルタ（F02.8）。
 *
 * <p>以下のエンドポイントに対してユーザー単位のレートリミットを適用する:</p>
 * <ul>
 *   <li>告知ウィザード実行 ({@code POST /api/v1/(teams|organizations)/*&#47;broadcast}):
 *       5分あたり5件 / ユーザー</li>
 * </ul>
 *
 * <p>認証済みユーザーのみが対象（{@link #shouldNotFilter} で未認証リクエストを除外）。
 * キーは基底の {@code "u:{userId}"} 形式を利用する。</p>
 *
 * <p><b>Valkey 化（第二陣B）</b>: 旧実装の Bucket4j + Caffeine（プロセス内カウント）は
 * ECS 複数タスク構成でタスク数に比例して実効上限が緩むため、
 * {@link ValkeyRateLimiter}（docs/security/06 §4.3）に移行した。
 * ウィンドウは <b>5分</b>（旧実装の Refill.greedy(5, Duration.ofMinutes(5)) と同等）。</p>
 */
@Component
public class BroadcastRateLimitFilter extends AbstractRateLimitFilter {

    /** broadcast エンドポイントを判定するパターン */
    private static final Pattern BROADCAST_PATTERN =
            Pattern.compile("^/api/v1/(teams|organizations)/[^/]+/broadcast$");

    /** 5分間で5件（旧実装の Refill.greedy と同等の固定ウィンドウ）*/
    private static final int LIMIT = 5;

    /** ウィンドウ長: 5分（旧実装から不変）*/
    private static final Duration WINDOW = Duration.ofMinutes(5);

    private static final String ZONE = "broadcast:send";

    public BroadcastRateLimitFilter(ObjectProvider<ValkeyRateLimiter> rateLimiterProvider) {
        super(rateLimiterProvider);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // GET は除外
        if ("GET".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        // 認証なしは除外（認証フィルタで処理される）
        if (!isAuthenticated()) {
            return true;
        }

        // broadcast エンドポイント以外はスキップ
        return !BROADCAST_PATTERN.matcher(request.getServletPath()).matches();
    }

    @Override
    protected RateLimitRule resolveRule(HttpServletRequest request) {
        if (!BROADCAST_PATTERN.matcher(request.getServletPath()).matches()) {
            return null;
        }
        return new RateLimitRule(ZONE, LIMIT, WINDOW);
    }
}
