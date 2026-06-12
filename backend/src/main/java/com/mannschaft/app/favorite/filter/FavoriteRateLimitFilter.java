package com.mannschaft.app.favorite.filter;

import com.mannschaft.app.common.ratelimit.AbstractRateLimitFilter;
import com.mannschaft.app.common.ratelimit.RateLimitRule;
import com.mannschaft.app.common.ratelimit.ValkeyRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.regex.Pattern;

/**
 * F02.9 お気に入りウィジェットのユーザー別レートリミットフィルタ。
 *
 * <p>設計書: {@code docs/features/F02.9_favorites_widget.md}
 *
 * <p>本フィルタが扱う対象:
 * <ul>
 *   <li>{@code GET    /api/v1/me/favorites}         ─ 120 req/分</li>
 *   <li>{@code GET    /api/v1/me/favorites/check}   ─ 240 req/分</li>
 *   <li>{@code POST   /api/v1/me/favorites}         ─ 30 req/時</li>
 *   <li>{@code DELETE /api/v1/me/favorites/{id}}    ─ 60 req/時</li>
 *   <li>{@code PATCH  /api/v1/me/favorites/reorder} ─ 30 req/時</li>
 * </ul>
 *
 * <p>パターンは「具体度の高いものから順に」評価する。
 * {@code /reorder} と {@code /check} を {@code /{id}} より先に判定すること。
 *
 * <p><b>Valkey 化（第二陣A）</b>: 旧実装の Bucket4j + Caffeine（プロセス内カウント）は
 * ECS 複数タスク構成でタスク数に比例して実効上限が緩むため、
 * {@link ValkeyRateLimiter}（docs/security/06 §4.3）に移行した。
 * 分ウィンドウ / 時間ウィンドウの混在は {@link RateLimitRule} の window フィールドで表現する。</p>
 */
@Component
public class FavoriteRateLimitFilter extends AbstractRateLimitFilter {

    // ──── レート定義 ─────────────────────────────
    private static final int LIST_RATE_PER_MINUTE = 120;
    private static final int CHECK_RATE_PER_MINUTE = 240;
    private static final int ADD_RATE_PER_HOUR = 30;
    private static final int DELETE_RATE_PER_HOUR = 60;
    private static final int REORDER_RATE_PER_HOUR = 30;

    private static final Duration WINDOW_MINUTE = Duration.ofMinutes(1);
    private static final Duration WINDOW_HOUR = Duration.ofHours(1);

    // ──── パスパターン ───────────────────────────

    /** 一覧取得 (GET) / 追加 (POST) の共通パス。 */
    private static final Pattern FAVORITES_ROOT_PATH =
            Pattern.compile("^/api/v1/me/favorites$");

    /** 並び替え: /{id} パターンより先に判定する。 */
    private static final Pattern REORDER_PATH =
            Pattern.compile("^/api/v1/me/favorites/reorder$");

    /** 登録状態チェック: /{id} パターンより先に判定する。 */
    private static final Pattern CHECK_PATH =
            Pattern.compile("^/api/v1/me/favorites/check$");

    /** 削除 (DELETE) / 1件取得 (GET) の {@code /{id}} パターン。 */
    private static final Pattern FAVORITE_ID_PATH =
            Pattern.compile("^/api/v1/me/favorites/[0-9a-fA-F-]{36}$");

    /** Valkey zone 接頭辞。 */
    private static final String ZONE_PREFIX = "favorite:";

    public FavoriteRateLimitFilter(ObjectProvider<ValkeyRateLimiter> rateLimiterProvider) {
        super(rateLimiterProvider);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getServletPath();

        if (("GET".equalsIgnoreCase(method) || "POST".equalsIgnoreCase(method))
                && FAVORITES_ROOT_PATH.matcher(path).matches()) {
            return false;
        }
        if ("GET".equalsIgnoreCase(method) && CHECK_PATH.matcher(path).matches()) {
            return false;
        }
        if ("PATCH".equalsIgnoreCase(method) && REORDER_PATH.matcher(path).matches()) {
            return false;
        }
        if ("DELETE".equalsIgnoreCase(method) && FAVORITE_ID_PATH.matcher(path).matches()) {
            return false;
        }
        return true;
    }

    @Override
    protected RateLimitRule resolveRule(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getServletPath();

        // 評価順序: より具体的なものから判定する（reorder/check を /{id} より先に）
        if ("PATCH".equalsIgnoreCase(method) && REORDER_PATH.matcher(path).matches()) {
            return new RateLimitRule(ZONE_PREFIX + "REORDER", REORDER_RATE_PER_HOUR, WINDOW_HOUR);
        }
        if ("GET".equalsIgnoreCase(method) && CHECK_PATH.matcher(path).matches()) {
            return new RateLimitRule(ZONE_PREFIX + "CHECK", CHECK_RATE_PER_MINUTE, WINDOW_MINUTE);
        }
        if ("DELETE".equalsIgnoreCase(method) && FAVORITE_ID_PATH.matcher(path).matches()) {
            return new RateLimitRule(ZONE_PREFIX + "DELETE", DELETE_RATE_PER_HOUR, WINDOW_HOUR);
        }
        if ("POST".equalsIgnoreCase(method) && FAVORITES_ROOT_PATH.matcher(path).matches()) {
            return new RateLimitRule(ZONE_PREFIX + "ADD", ADD_RATE_PER_HOUR, WINDOW_HOUR);
        }
        if ("GET".equalsIgnoreCase(method) && FAVORITES_ROOT_PATH.matcher(path).matches()) {
            return new RateLimitRule(ZONE_PREFIX + "LIST", LIST_RATE_PER_MINUTE, WINDOW_MINUTE);
        }
        return null;
    }
}
