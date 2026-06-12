package com.mannschaft.app.dashboard;

import com.mannschaft.app.common.ratelimit.AbstractRateLimitFilter;
import com.mannschaft.app.common.ratelimit.RateLimitRule;
import com.mannschaft.app.common.ratelimit.ValkeyRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * F22.1: 横スワイプ・ダッシュボード scope-tabs のレートリミットフィルタ。
 *
 * <p>設計書 02_api_design.md §5 に従い、{@code PUT /api/v1/dashboard/scope-tabs/order}
 * に対してユーザー単位 30 req/分のレートリミットを適用する（並べ替え確定の連打防止）。
 * GET 側は読み取り・ページ送りの連打を許容するため対象外（§5）。</p>
 *
 * <p>JWT 認証後に確定した SecurityContext から userId を解決するため、SecurityConfig では
 * {@code addFilterAfter(..., JwtAuthenticationFilter.class)} で登録する
 * （{@link com.mannschaft.app.actionmemo.ActionMemoRateLimitFilter} と同方針）。
 * {@link com.mannschaft.app.config.SecurityConfig#dashboardScopeTabRateLimitFilterRegistration}
 * で @Component 自動登録を無効化し、二重登録を防ぐ。</p>
 *
 * <p><b>Valkey 化（第二陣B）</b>: 旧実装の Bucket4j + Caffeine（プロセス内カウント）は
 * ECS 複数タスク構成でタスク数に比例して実効上限が緩むため、
 * {@link ValkeyRateLimiter}（docs/security/06 §4.3）に移行した。
 * エンドポイント判定と (zone, limit, window) 宣言のみ本クラスが持ち、
 * カウント・§4.3 標準ヘッダー・429 応答は {@link AbstractRateLimitFilter} が担う。</p>
 */
@Component
public class DashboardScopeTabRateLimitFilter extends AbstractRateLimitFilter {

    private static final String PATH = "/api/v1/dashboard/scope-tabs/order";
    private static final String METHOD = "PUT";
    private static final int CAPACITY_PER_MINUTE = 30;
    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final String ZONE = "dashboard:scope-tabs-order";

    public DashboardScopeTabRateLimitFilter(ObjectProvider<ValkeyRateLimiter> rateLimiterProvider) {
        super(rateLimiterProvider);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !(PATH.equals(request.getServletPath()) && METHOD.equalsIgnoreCase(request.getMethod()));
    }

    @Override
    protected RateLimitRule resolveRule(HttpServletRequest request) {
        if (PATH.equals(request.getServletPath()) && METHOD.equalsIgnoreCase(request.getMethod())) {
            return new RateLimitRule(ZONE, CAPACITY_PER_MINUTE, WINDOW);
        }
        return null;
    }
}
