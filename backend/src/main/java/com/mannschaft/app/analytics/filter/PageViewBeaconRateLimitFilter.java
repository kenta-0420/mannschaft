package com.mannschaft.app.analytics.filter;

import com.mannschaft.app.common.ratelimit.AbstractRateLimitFilter;
import com.mannschaft.app.common.ratelimit.RateLimitRule;
import com.mannschaft.app.common.ratelimit.ValkeyRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * F10.8 計測ビーコン {@code POST /api/v1/page-views} の IP 単位レートリミット。
 *
 * <p>計測ビーコンは未認証を許容する公開エンドポイント（ゲスト計測）のため、ユーザー識別子が無く
 * <b>IP アドレスのみ</b>で制御する。閾値は {@code docs/security/06 §4.2} の「公開 API（認証不要）=
 * IP・60 req/分」に従う（設計書 §7.3）。超過時は {@link AbstractRateLimitFilter} 標準の 429 応答
 * （ボディ {@code {"error":"Too many requests"}} + {@code Retry-After} + {@code X-RateLimit-*}）で返る
 * ため、特定エラーコード body は載せない（AC-07）。</p>
 *
 * <p>{@code visitor_id}（クライアント cookie）は詐称容易なためレート制限キーには使わず、IP を正とする
 * （設計書 §7.3）。Valkey 固定ウィンドウ・Lua 原子化・fail-open は {@link ValkeyRateLimiter} が担う。</p>
 *
 * <p>登録方式: {@code SecurityConfig#pageViewBeaconRateLimitFilterRegistration} で @Component 由来の
 * サーブレットフィルター自動登録を無効化し、Spring Security の {@code addFilterBefore} 経由のみで動作させる
 * （既存 {@code AdPublicEndpointRateLimitFilter} と同方式）。</p>
 */
@Component
public class PageViewBeaconRateLimitFilter extends AbstractRateLimitFilter {

    private static final String PAGE_VIEW_PATH = "/api/v1/page-views";

    /** IP・60 req/分（docs/security/06 §4.2 公開 API 標準閾値）。 */
    private static final int RATE_PER_MINUTE = 60;

    private static final Duration WINDOW = Duration.ofMinutes(1);

    /** 計測ビーコン用 Valkey zone。 */
    private static final String ZONE = "page-view:beacon";

    public PageViewBeaconRateLimitFilter(ObjectProvider<ValkeyRateLimiter> rateLimiterProvider) {
        super(rateLimiterProvider);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // POST /api/v1/page-views のみを対象にする
        return !(PAGE_VIEW_PATH.equals(request.getServletPath())
                && "POST".equalsIgnoreCase(request.getMethod()));
    }

    @Override
    protected RateLimitRule resolveRule(HttpServletRequest request) {
        if (PAGE_VIEW_PATH.equals(request.getServletPath())) {
            return new RateLimitRule(ZONE, RATE_PER_MINUTE, WINDOW);
        }
        return null;
    }

    /**
     * 常に IP キーを返す（未認証エンドポイントのため）。X-Forwarded-For 先頭値を優先する。
     */
    @Override
    protected String resolveClientKey(HttpServletRequest request) {
        return "ip:" + resolveIp(request);
    }
}
