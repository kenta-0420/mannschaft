package com.mannschaft.app.advertising.campaign.filter;

import com.mannschaft.app.common.ratelimit.AbstractRateLimitFilter;
import com.mannschaft.app.common.ratelimit.RateLimitResult;
import com.mannschaft.app.common.ratelimit.RateLimitRule;
import com.mannschaft.app.common.ratelimit.ValkeyRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;

/**
 * F09.17 Phase 11-b 公開エンドポイント (unsubscribe / 開封ピクセル) の IP 単位レートリミット。
 *
 * <p>対象:</p>
 * <ul>
 *   <li>{@code GET  /api/v1/ads/unsubscribe}     ─ 60 req/分（設計書 §6）</li>
 *   <li>{@code POST /api/v1/ads/unsubscribe}     ─ 60 req/分（F09.17 残課題 4 SPA POST 共通）</li>
 *   <li>{@code GET  /api/v1/ads/pixels/open}     ─ 600 req/分（メーラー再フェッチ考慮）</li>
 * </ul>
 *
 * <p>認証不要エンドポイントのためユーザー識別子が無く、IP アドレスのみで制御する。
 * X-Forwarded-For は経路に reverse proxy がある場合のみ意味があるため、
 * 先頭値があれば使用しつつ {@code request.getRemoteAddr()} にフォールバックする。</p>
 *
 * <p>本フィルタは 60/分の unsubscribe と 600/分の pixel をそれぞれ別 zone で管理し、
 * 一方の枯渇が他方に波及しないようにする。</p>
 *
 * <p>登録方式: {@link com.mannschaft.app.config.SecurityConfig#adPublicEndpointRateLimitFilterRegistration}
 * で @Component 自動登録を無効化し、SecurityConfig の {@code addFilterBefore} 経由のみで動作させる。</p>
 *
 * <p><b>Valkey 化（第二陣B）</b>: 旧実装の Bucket4j + Caffeine（プロセス内カウント）は
 * ECS 複数タスク構成でタスク数に比例して実効上限が緩むため、
 * {@link ValkeyRateLimiter}（docs/security/06 §4.3）に移行した。
 * カウント・§4.3 標準ヘッダー・429 応答は {@link AbstractRateLimitFilter} が担う。</p>
 */
@Component
public class AdPublicEndpointRateLimitFilter extends AbstractRateLimitFilter {

    private static final String UNSUBSCRIBE_PATH = "/api/v1/ads/unsubscribe";
    private static final String OPEN_PIXEL_PATH = "/api/v1/ads/pixels/open";

    private static final int UNSUBSCRIBE_RATE_PER_MINUTE = 60;
    private static final int OPEN_PIXEL_RATE_PER_MINUTE = 600;

    private static final Duration WINDOW = Duration.ofMinutes(1);

    /** unsubscribe 用 Valkey zone */
    private static final String ZONE_UNSUBSCRIBE = "ad-public:unsubscribe";

    /** 開封ピクセル用 Valkey zone */
    private static final String ZONE_PIXEL = "ad-public:pixel-open";

    public AdPublicEndpointRateLimitFilter(ObjectProvider<ValkeyRateLimiter> rateLimiterProvider) {
        super(rateLimiterProvider);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getServletPath();
        // unsubscribe は GET (後方互換ワンクリック) / POST (SPA 確定) の両方を 60/min で守る
        if (UNSUBSCRIBE_PATH.equals(path)
                && ("GET".equalsIgnoreCase(method) || "POST".equalsIgnoreCase(method))) {
            return false;
        }
        // 開封ピクセルは GET のみ
        if (OPEN_PIXEL_PATH.equals(path) && "GET".equalsIgnoreCase(method)) {
            return false;
        }
        return true;
    }

    @Override
    protected RateLimitRule resolveRule(HttpServletRequest request) {
        String path = request.getServletPath();
        if (UNSUBSCRIBE_PATH.equals(path)) {
            return new RateLimitRule(ZONE_UNSUBSCRIBE, UNSUBSCRIBE_RATE_PER_MINUTE, WINDOW);
        }
        if (OPEN_PIXEL_PATH.equals(path)) {
            return new RateLimitRule(ZONE_PIXEL, OPEN_PIXEL_RATE_PER_MINUTE, WINDOW);
        }
        return null;
    }

    /**
     * IP キーを直接返す（X-Forwarded-For 優先）。
     *
     * <p>認証不要エンドポイントのためユーザー識別子が無く、IP のみで制御する。
     * 基底の {@link #resolveClientKey} は認証済みならユーザーキーを返すが、
     * 本フィルタは常に IP キーを使用する（旧実装の挙動と互換）。</p>
     */
    @Override
    protected String resolveClientKey(HttpServletRequest request) {
        return "ip:" + resolveIp(request);
    }
}
