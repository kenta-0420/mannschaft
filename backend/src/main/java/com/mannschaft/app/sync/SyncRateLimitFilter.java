package com.mannschaft.app.sync;

import com.mannschaft.app.common.ratelimit.AbstractRateLimitFilter;
import com.mannschaft.app.common.ratelimit.RateLimitRule;
import com.mannschaft.app.common.ratelimit.ValkeyRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * F11.1 オフライン同期 API の IP ベースレートリミットフィルタ。
 *
 * <ul>
 *   <li>POST /api/v1/sync: 1分10回（一括同期は重い処理のため厳しめ）</li>
 *   <li>GET/PATCH/DELETE /api/v1/sync/conflicts/**: 1分60回</li>
 * </ul>
 *
 * <p><b>Valkey 化（第二陣A）</b>: 旧実装の Bucket4j + Caffeine（プロセス内カウント）は
 * ECS 複数タスク構成でタスク数に比例して実効上限が緩むため、
 * {@link ValkeyRateLimiter}（docs/security/06 §4.3）に移行した。
 * エンドポイント判定と (zone, limit, window) 宣言のみ本クラスが持ち、
 * カウント・§4.3 標準ヘッダー・429 応答は {@link AbstractRateLimitFilter} が担う。</p>
 *
 * <p>キーは IP ベース（認証不要エンドポイントのため）。
 * 基底の {@link AbstractRateLimitFilter#resolveClientKey} は認証済みなら "u:{userId}"、
 * 未認証なら "ip:{ip}" を返すが、同期 API は未認証リクエストもあり得るため
 * IP ベースフォールバックが正常に動作する。</p>
 */
@Component
public class SyncRateLimitFilter extends AbstractRateLimitFilter {

    private static final String SYNC_PATH = "/api/v1/sync";
    private static final int SYNC_LIMIT = 10;
    private static final int CONFLICT_LIMIT = 60;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private static final String ZONE_PREFIX = "sync:";

    public SyncRateLimitFilter(ObjectProvider<ValkeyRateLimiter> rateLimiterProvider) {
        super(rateLimiterProvider);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return !path.startsWith(SYNC_PATH);
    }

    @Override
    protected RateLimitRule resolveRule(HttpServletRequest request) {
        String path = request.getServletPath();
        String method = request.getMethod();

        if (SYNC_PATH.equals(path) && "POST".equalsIgnoreCase(method)) {
            // POST /api/v1/sync: 1分10回
            return new RateLimitRule(ZONE_PREFIX + "POST", SYNC_LIMIT, WINDOW);
        } else if (path.startsWith(SYNC_PATH + "/conflicts")) {
            // conflicts 系: 1分60回
            return new RateLimitRule(ZONE_PREFIX + "CONFLICTS", CONFLICT_LIMIT, WINDOW);
        }
        return null;
    }
}
