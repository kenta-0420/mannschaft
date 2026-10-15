package com.mannschaft.app.errorreport;

import com.mannschaft.app.common.ratelimit.AbstractRateLimitFilter;
import com.mannschaft.app.common.ratelimit.RateLimitRule;
import com.mannschaft.app.common.ratelimit.ValkeyRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * エラーレポートAPIのIPベースレート制限フィルタ。
 * {@code POST /api/v1/error-reports} のみに適用し、他APIに影響しない。
 * 同一IPから 10 回/分。超過時は 429 Too Many Requests。
 *
 * <p><b>Valkey 化（第二陣B）</b>: 旧実装の Bucket4j + Caffeine（プロセス内カウント）は
 * ECS 複数タスク構成でタスク数に比例して実効上限が緩むため、
 * {@link ValkeyRateLimiter}（docs/security/06 §4.3）に移行した。
 * IP キー解決・§4.3 標準ヘッダー・429 応答は {@link AbstractRateLimitFilter} が担う。
 * 本フィルタは認証不要エンドポイントのため IP キーのみを使用する（基底の
 * {@link AbstractRateLimitFilter#resolveClientKey} は認証済みならユーザーキーを優先するが、
 * error-reports は permitAll のため実際には常に IP キーになる）。</p>
 */
@Component
public class ErrorReportRateLimitFilter extends AbstractRateLimitFilter {

    private static final int LIMIT_PER_MINUTE = 10;
    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final String ZONE = "errorreport:create";

    public ErrorReportRateLimitFilter(ObjectProvider<ValkeyRateLimiter> rateLimiterProvider) {
        super(rateLimiterProvider);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"/api/v1/error-reports".equals(request.getServletPath())
                || !"POST".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected RateLimitRule resolveRule(HttpServletRequest request) {
        if ("/api/v1/error-reports".equals(request.getServletPath())
                && "POST".equalsIgnoreCase(request.getMethod())) {
            return new RateLimitRule(ZONE, LIMIT_PER_MINUTE, WINDOW);
        }
        return null;
    }

    /**
     * IP キーを直接返す。
     * error-reports は permitAll のため認証ユーザーが来ることもあるが、
     * IP ベースのレートリミットで統一する（旧実装の挙動と互換）。
     */
    @Override
    protected String resolveClientKey(HttpServletRequest request) {
        return "ip:" + resolveIp(request);
    }
}
