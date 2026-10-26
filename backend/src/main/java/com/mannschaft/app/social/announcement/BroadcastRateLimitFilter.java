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
 *   <li>宛先プレビュー ({@code POST /api/v1/organizations/*&#47;broadcast/audience-preview}):
 *       1分あたり60件 / ユーザー（F01.2.1 §10.10。送信とは別の枠）</li>
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

    /** 宛先プレビュー（F01.2.1 §10.10・AC-G102）を判定するパターン。 */
    private static final Pattern PREVIEW_PATTERN =
            Pattern.compile("^/api/v1/organizations/[^/]+/broadcast/audience-preview$");

    /** 宛先プレビュー: 1分間で60件（ウィザードが入力のたびに 300ms デバウンスで呼ぶため送信より緩い）。 */
    private static final int PREVIEW_LIMIT = 60;

    private static final Duration PREVIEW_WINDOW = Duration.ofMinutes(1);

    private static final String PREVIEW_ZONE = "broadcast:preview";

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

        // broadcast・宛先プレビュー以外はスキップ
        return resolveRule(request) == null;
    }

    @Override
    protected RateLimitRule resolveRule(HttpServletRequest request) {
        String path = request.getServletPath();
        if (BROADCAST_PATTERN.matcher(path).matches()) {
            return new RateLimitRule(ZONE, LIMIT, WINDOW);
        }
        if (PREVIEW_PATTERN.matcher(path).matches()) {
            return new RateLimitRule(PREVIEW_ZONE, PREVIEW_LIMIT, PREVIEW_WINDOW);
        }
        return null;
    }
}
