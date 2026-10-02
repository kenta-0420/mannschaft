package com.mannschaft.app.village;

import com.mannschaft.app.common.ratelimit.AbstractRateLimitFilter;
import com.mannschaft.app.common.ratelimit.RateLimitRule;
import com.mannschaft.app.common.ratelimit.ValkeyRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * F17.2 機能⑤ 加入前相性表示のレートリミットフィルタ（設計書 §8.4 緩和2）。
 *
 * <p>{@code GET /api/v1/villages/{villageId}/affinity/me} を
 * <strong>userId × villageId</strong> 単位で 1 分間に 30 回に制限する。</p>
 *
 * <p><b>なぜ userId 単独でなく村ごとにカウントするか</b>: 攻撃者が「自分の所属村集合を1村ずつ変えながら
 * 同一村の相性を何度も引く」差分攻撃（§8.4）を<strong>村単位で捕捉</strong>するため。制限主体キーに
 * villageId を含めることで、村ごとに独立したカウンタになり、単一村への反復クエリを弾ける。</p>
 *
 * <p>登録方式: {@link com.mannschaft.app.config.SecurityConfig#villageAffinityRateLimitFilterRegistration}
 * で @Component 自動登録を無効化し、SecurityConfig の {@code addFilterAfter(・, JwtAuthenticationFilter.class)}
 * 経由のみで動作させる（JWT 認証後の確定した SecurityContext から userId を解決する）。
 * カウント・§4.3 標準ヘッダー・429 応答は {@link AbstractRateLimitFilter} が担う。</p>
 */
@Component
public class VillageAffinityRateLimitFilter extends AbstractRateLimitFilter {

    /** 対象: GET /api/v1/villages/{villageId}/affinity/me（villageId をキャプチャ）。 */
    private static final Pattern TARGET_PATH =
            Pattern.compile("^/api/v1/villages/([^/]+)/affinity/me/?$");

    /** 1 分間あたりの上限回数（§8.4 既定・PR レビューで変更可）。 */
    private static final int CAPACITY_PER_MINUTE = 30;

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private static final String ZONE = "village:affinity";

    public VillageAffinityRateLimitFilter(ObjectProvider<ValkeyRateLimiter> rateLimiterProvider) {
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

    /**
     * 制限主体キーに villageId を含める（{@code u:{userId}:v:{villageId}}）。
     * 認証済み前提（JWT 認証後段で動くため）だが、未認証時は基底の {@code ip:{ip}} に villageId を付す。
     */
    @Override
    protected String resolveClientKey(HttpServletRequest request) {
        String base = super.resolveClientKey(request);
        String villageId = extractVillageId(request);
        return villageId == null ? base : base + ":v:" + villageId;
    }

    private boolean isTarget(HttpServletRequest request) {
        return "GET".equalsIgnoreCase(request.getMethod())
                && TARGET_PATH.matcher(request.getServletPath()).matches();
    }

    private String extractVillageId(HttpServletRequest request) {
        Matcher m = TARGET_PATH.matcher(request.getServletPath());
        return m.matches() ? m.group(1) : null;
    }
}
