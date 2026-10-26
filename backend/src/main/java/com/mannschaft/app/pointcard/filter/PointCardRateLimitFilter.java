package com.mannschaft.app.pointcard.filter;

import com.mannschaft.app.common.ratelimit.AbstractRateLimitFilter;
import com.mannschaft.app.common.ratelimit.RateLimitRule;
import com.mannschaft.app.common.ratelimit.ValkeyRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.regex.Pattern;

/**
 * F18 ポイントカードウォレットのユーザー別レートリミットフィルタ。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §9.5
 *
 * <p>本フィルタが扱う対象:
 * <ul>
 *   <li>{@code GET    /api/v1/point-cards/providers}      ─ 60 req/分（2A）</li>
 *   <li>{@code PUT    /api/v1/point-cards/settings}       ─ 10 req/時（2A）</li>
 *   <li>{@code POST   /api/v1/point-cards}                ─ 30 req/時（2B 追加）</li>
 *   <li>{@code GET    /api/v1/point-cards/{id}}           ─ 120 req/分（2B 追加）</li>
 *   <li>{@code POST   /api/v1/point-cards/{id}/used}      ─ 600 req/時（2B 追加）</li>
 *   <li>{@code GET    /api/v1/point-cards/groups}         ─ 60 req/分（S3 追加）</li>
 *   <li>{@code POST   /api/v1/point-cards/groups}         ─ 30 req/時（S3 追加）</li>
 *   <li>{@code POST   /api/v1/point-cards/groups/{id}/presentation-start} ─ 600 req/時（S3 追加）</li>
 *   <li>{@code POST   /api/v1/organizations/{orgId}/point-cards/providers}                ─ 30 req/時（2B 追加）</li>
 *   <li>{@code PATCH  /api/v1/organizations/{orgId}/point-cards/providers/{id}}           ─ 30 req/時（2B 追加）</li>
 *   <li>{@code DELETE /api/v1/organizations/{orgId}/point-cards/providers/{id}}           ─ 30 req/時（2B 追加）</li>
 *   <li>{@code POST   /api/v1/organizations/{orgId}/point-cards/{cardId}/stamps} ─ 300 req/時（2C 追加）</li>
 *   <li>{@code GET    /api/v1/organizations/{orgId}/point-cards/stamps} ─ 120 req/分（2C 追加）</li>
 *   <li>{@code GET    /api/v1/organizations/{orgId}/point-cards/{cardId}/stamps} ─ 120 req/分（2C 追加）</li>
 *   <li>{@code POST   /api/v1/point-cards/{cardId}/share-tokens} ─ 60 req/時（P3 2A 追加）</li>
 *   <li>{@code POST   /api/v1/organizations/{orgId}/point-cards/resolve-by-token} ─ 600 req/時（P3 2A 追加）</li>
 *   <li>{@code POST   /api/v1/organizations/{orgId}/point-cards/{cardId}/balance-events} ─ 300 req/時（Phase 3 2B 追加）</li>
 *   <li>{@code GET    /api/v1/organizations/{orgId}/point-cards/balance-events} ─ 120 req/分（Phase 3 2B 追加）</li>
 *   <li>{@code GET    /api/v1/organizations/{orgId}/point-cards/{cardId}/balance-events} ─ 120 req/分（Phase 3 2B 追加）</li>
 * </ul>
 *
 * <p>パターンは「具体度の高いものから順に」評価する。たとえば
 * {@code /point-cards/{id}/used} を {@code /point-cards/{id}} より先に判定すること。
 *
 * <p><b>Valkey 化（第二陣A）</b>: 旧実装の Bucket4j + Caffeine（プロセス内カウント）は
 * ECS 複数タスク構成でタスク数に比例して実効上限が緩むため、
 * {@link ValkeyRateLimiter}（docs/security/06 §4.3）に移行した。
 * 分ウィンドウ / 時間ウィンドウの混在は {@link RateLimitRule} の window フィールドで表現する。</p>
 */
@Component
public class PointCardRateLimitFilter extends AbstractRateLimitFilter {

    // ──── レート定義 ─────────────────────────────
    private static final int PROVIDERS_RATE_PER_MINUTE = 60;
    private static final int SETTINGS_PUT_RATE_PER_HOUR = 10;
    private static final int CREATE_CARD_RATE_PER_HOUR = 30;
    private static final int GET_DETAIL_RATE_PER_MINUTE = 120;
    private static final int RECORD_USED_RATE_PER_HOUR = 600;
    // S3 グループ機能
    private static final int LIST_GROUPS_RATE_PER_MINUTE = 60;
    private static final int CREATE_GROUP_RATE_PER_HOUR = 30;
    private static final int PRESENTATION_RATE_PER_HOUR = 600;
    // S2B 自店プロバイダー CRUD（POST / PATCH / DELETE 共通で 30/h）
    private static final int ORG_PROVIDER_CUD_RATE_PER_HOUR = 30;
    // 2C スタンプ押印 / 履歴
    private static final int STAMP_POST_RATE_PER_HOUR = 300;
    private static final int STAMP_LIST_RATE_PER_MINUTE = 120;
    // P3 2A QR 自動特定（顧客側発行 / 店主側 resolve）
    private static final int SHARE_TOKEN_GENERATE_RATE_PER_HOUR = 60;
    private static final int SHARE_TOKEN_RESOLVE_RATE_PER_HOUR = 600;
    // Phase 3 2B 残高型 CHARGE/SPENT/REFUND / 履歴
    private static final int BALANCE_POST_RATE_PER_HOUR = 300;
    private static final int BALANCE_LIST_RATE_PER_MINUTE = 120;

    private static final Duration WINDOW_MINUTE = Duration.ofMinutes(1);
    private static final Duration WINDOW_HOUR = Duration.ofHours(1);

    // ──── パスパターン ───────────────────────────
    private static final Pattern PROVIDERS_PATH =
            Pattern.compile("^/api/v1/point-cards/providers$");

    private static final Pattern SETTINGS_PATH =
            Pattern.compile("^/api/v1/point-cards/settings$");

    /** カード一覧 (GET /) と 作成 (POST /) の共通パス。 */
    private static final Pattern CARDS_ROOT_PATH =
            Pattern.compile("^/api/v1/point-cards$");

    /** カード利用記録: GET 詳細パターンより先にマッチ判定する。 */
    private static final Pattern CARD_USED_PATH =
            Pattern.compile("^/api/v1/point-cards/[0-9a-fA-F-]{36}/used$");

    /** カード詳細 / 更新 / 削除の {@code /{id}} パターン。 */
    private static final Pattern CARD_ID_PATH =
            Pattern.compile("^/api/v1/point-cards/[0-9a-fA-F-]{36}$");

    /** グループ一覧 (GET) / 作成 (POST) の共通パス。 */
    private static final Pattern GROUPS_ROOT_PATH =
            Pattern.compile("^/api/v1/point-cards/groups$");

    /** グループ提示モード起動パターン。具体度が高いので /{id} より先に判定する。 */
    private static final Pattern GROUP_PRESENTATION_PATH =
            Pattern.compile("^/api/v1/point-cards/groups/[0-9a-fA-F-]{36}/presentation-start$");

    /** Org プロバイダー一覧 / 新規発行のルートパス。S2B 追加。 */
    private static final Pattern ORG_PROVIDERS_ROOT_PATH =
            Pattern.compile("^/api/v1/organizations/[0-9]+/point-cards/providers$");

    /** Org プロバイダー個別操作（編集 / 停止）パス。S2B 追加。 */
    private static final Pattern ORG_PROVIDER_ID_PATH =
            Pattern.compile("^/api/v1/organizations/[0-9]+/point-cards/providers/[0-9a-fA-F-]{36}$");

    /** 組織配下: 単一カードへのスタンプ押印 / 履歴。具体度が高いので組織一覧パターンより先に判定。 */
    private static final Pattern ORG_STAMP_CARD_PATH =
            Pattern.compile("^/api/v1/organizations/\\d+/point-cards/[0-9a-fA-F-]{36}/stamps$");

    /** 組織配下: スタンプ履歴一覧。 */
    private static final Pattern ORG_STAMP_LIST_PATH =
            Pattern.compile("^/api/v1/organizations/\\d+/point-cards/stamps$");

    /** P3 2A: 顧客側 一時トークン発行。{@code /share-tokens} は {@code CARD_ID_PATH} より具体度高い。 */
    private static final Pattern SHARE_TOKEN_GENERATE_PATH =
            Pattern.compile("^/api/v1/point-cards/[0-9a-fA-F-]{36}/share-tokens$");

    /** P3 2A: 店主側 一時トークン resolve。 */
    private static final Pattern SHARE_TOKEN_RESOLVE_PATH =
            Pattern.compile("^/api/v1/organizations/\\d+/point-cards/resolve-by-token$");

    /** 組織配下: 単一カードの残高イベント記録 / 履歴。具体度高いので組織一覧パターンより先に判定。 */
    private static final Pattern ORG_BALANCE_CARD_PATH =
            Pattern.compile("^/api/v1/organizations/\\d+/point-cards/[0-9a-fA-F-]{36}/balance-events$");

    /** 組織配下: 残高変動履歴一覧。 */
    private static final Pattern ORG_BALANCE_LIST_PATH =
            Pattern.compile("^/api/v1/organizations/\\d+/point-cards/balance-events$");

    /** Valkey zone 接頭辞。 */
    private static final String ZONE_PREFIX = "pointcard:";

    public PointCardRateLimitFilter(ObjectProvider<ValkeyRateLimiter> rateLimiterProvider) {
        super(rateLimiterProvider);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getServletPath();

        if ("GET".equalsIgnoreCase(method) && PROVIDERS_PATH.matcher(path).matches()) {
            return false;
        }
        if ("PUT".equalsIgnoreCase(method) && SETTINGS_PATH.matcher(path).matches()) {
            return false;
        }
        if ("POST".equalsIgnoreCase(method) && CARDS_ROOT_PATH.matcher(path).matches()) {
            return false;
        }
        if ("GET".equalsIgnoreCase(method) && CARD_ID_PATH.matcher(path).matches()) {
            return false;
        }
        if ("POST".equalsIgnoreCase(method) && CARD_USED_PATH.matcher(path).matches()) {
            return false;
        }
        // S3 グループ機能（GROUPS_ROOT_PATH は CARD_ID_PATH より具体度が高いので問題ない）
        if (("GET".equalsIgnoreCase(method) || "POST".equalsIgnoreCase(method))
                && GROUPS_ROOT_PATH.matcher(path).matches()) {
            return false;
        }
        if ("POST".equalsIgnoreCase(method) && GROUP_PRESENTATION_PATH.matcher(path).matches()) {
            return false;
        }
        // S2B Org プロバイダー CUD（POST root / PATCH /{id} / DELETE /{id} を対象に）
        if ("POST".equalsIgnoreCase(method) && ORG_PROVIDERS_ROOT_PATH.matcher(path).matches()) {
            return false;
        }
        if (("PATCH".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method))
                && ORG_PROVIDER_ID_PATH.matcher(path).matches()) {
            return false;
        }
        // 2C: 組織配下スタンプ系
        if ("POST".equalsIgnoreCase(method) && ORG_STAMP_CARD_PATH.matcher(path).matches()) {
            return false;
        }
        if ("GET".equalsIgnoreCase(method) && ORG_STAMP_CARD_PATH.matcher(path).matches()) {
            return false;
        }
        if ("GET".equalsIgnoreCase(method) && ORG_STAMP_LIST_PATH.matcher(path).matches()) {
            return false;
        }
        // P3 2A: 一時トークン発行 / resolve
        if ("POST".equalsIgnoreCase(method) && SHARE_TOKEN_GENERATE_PATH.matcher(path).matches()) {
            return false;
        }
        if ("POST".equalsIgnoreCase(method) && SHARE_TOKEN_RESOLVE_PATH.matcher(path).matches()) {
            return false;
        }
        // Phase 3 2B: 組織配下 残高型イベント
        if ("POST".equalsIgnoreCase(method) && ORG_BALANCE_CARD_PATH.matcher(path).matches()) {
            return false;
        }
        if ("GET".equalsIgnoreCase(method) && ORG_BALANCE_CARD_PATH.matcher(path).matches()) {
            return false;
        }
        if ("GET".equalsIgnoreCase(method) && ORG_BALANCE_LIST_PATH.matcher(path).matches()) {
            return false;
        }
        return true;
    }

    @Override
    protected RateLimitRule resolveRule(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getServletPath();

        // 評価順序: より具体的なものから判定する
        // S2B Org プロバイダー個別操作（PATCH / DELETE）— /{id} 形式の方が ROOT より具体度高
        if (("PATCH".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method))
                && ORG_PROVIDER_ID_PATH.matcher(path).matches()) {
            return new RateLimitRule(ZONE_PREFIX + "ORG_PROVIDER_CUD", ORG_PROVIDER_CUD_RATE_PER_HOUR, WINDOW_HOUR);
        }
        if ("POST".equalsIgnoreCase(method) && ORG_PROVIDERS_ROOT_PATH.matcher(path).matches()) {
            return new RateLimitRule(ZONE_PREFIX + "ORG_PROVIDER_CUD", ORG_PROVIDER_CUD_RATE_PER_HOUR, WINDOW_HOUR);
        }
        if ("POST".equalsIgnoreCase(method) && ORG_STAMP_CARD_PATH.matcher(path).matches()) {
            return new RateLimitRule(ZONE_PREFIX + "STAMP_POST", STAMP_POST_RATE_PER_HOUR, WINDOW_HOUR);
        }
        if ("GET".equalsIgnoreCase(method) && ORG_STAMP_CARD_PATH.matcher(path).matches()) {
            return new RateLimitRule(ZONE_PREFIX + "STAMP_CARD_LIST", STAMP_LIST_RATE_PER_MINUTE, WINDOW_MINUTE);
        }
        if ("GET".equalsIgnoreCase(method) && ORG_STAMP_LIST_PATH.matcher(path).matches()) {
            return new RateLimitRule(ZONE_PREFIX + "STAMP_ORG_LIST", STAMP_LIST_RATE_PER_MINUTE, WINDOW_MINUTE);
        }
        if ("POST".equalsIgnoreCase(method) && ORG_BALANCE_CARD_PATH.matcher(path).matches()) {
            return new RateLimitRule(ZONE_PREFIX + "BALANCE_POST", BALANCE_POST_RATE_PER_HOUR, WINDOW_HOUR);
        }
        if ("GET".equalsIgnoreCase(method) && ORG_BALANCE_CARD_PATH.matcher(path).matches()) {
            return new RateLimitRule(ZONE_PREFIX + "BALANCE_CARD_LIST", BALANCE_LIST_RATE_PER_MINUTE, WINDOW_MINUTE);
        }
        if ("GET".equalsIgnoreCase(method) && ORG_BALANCE_LIST_PATH.matcher(path).matches()) {
            return new RateLimitRule(ZONE_PREFIX + "BALANCE_ORG_LIST", BALANCE_LIST_RATE_PER_MINUTE, WINDOW_MINUTE);
        }
        if ("POST".equalsIgnoreCase(method) && GROUP_PRESENTATION_PATH.matcher(path).matches()) {
            return new RateLimitRule(ZONE_PREFIX + "PRESENTATION", PRESENTATION_RATE_PER_HOUR, WINDOW_HOUR);
        }
        if ("POST".equalsIgnoreCase(method) && GROUPS_ROOT_PATH.matcher(path).matches()) {
            return new RateLimitRule(ZONE_PREFIX + "CREATE_GROUP", CREATE_GROUP_RATE_PER_HOUR, WINDOW_HOUR);
        }
        if ("GET".equalsIgnoreCase(method) && GROUPS_ROOT_PATH.matcher(path).matches()) {
            return new RateLimitRule(ZONE_PREFIX + "LIST_GROUPS", LIST_GROUPS_RATE_PER_MINUTE, WINDOW_MINUTE);
        }
        if ("POST".equalsIgnoreCase(method) && SHARE_TOKEN_GENERATE_PATH.matcher(path).matches()) {
            // /share-tokens は CARD_USED_PATH（/used）と同じく CARD_ID_PATH より具体度が高い。
            return new RateLimitRule(ZONE_PREFIX + "SHARE_TOKEN_GENERATE", SHARE_TOKEN_GENERATE_RATE_PER_HOUR, WINDOW_HOUR);
        }
        if ("POST".equalsIgnoreCase(method) && SHARE_TOKEN_RESOLVE_PATH.matcher(path).matches()) {
            return new RateLimitRule(ZONE_PREFIX + "SHARE_TOKEN_RESOLVE", SHARE_TOKEN_RESOLVE_RATE_PER_HOUR, WINDOW_HOUR);
        }
        if ("POST".equalsIgnoreCase(method) && CARD_USED_PATH.matcher(path).matches()) {
            return new RateLimitRule(ZONE_PREFIX + "RECORD_USED", RECORD_USED_RATE_PER_HOUR, WINDOW_HOUR);
        }
        if ("GET".equalsIgnoreCase(method) && CARD_ID_PATH.matcher(path).matches()) {
            return new RateLimitRule(ZONE_PREFIX + "GET_DETAIL", GET_DETAIL_RATE_PER_MINUTE, WINDOW_MINUTE);
        }
        if ("POST".equalsIgnoreCase(method) && CARDS_ROOT_PATH.matcher(path).matches()) {
            return new RateLimitRule(ZONE_PREFIX + "CREATE_CARD", CREATE_CARD_RATE_PER_HOUR, WINDOW_HOUR);
        }
        if ("GET".equalsIgnoreCase(method) && PROVIDERS_PATH.matcher(path).matches()) {
            return new RateLimitRule(ZONE_PREFIX + "PROVIDERS", PROVIDERS_RATE_PER_MINUTE, WINDOW_MINUTE);
        }
        if ("PUT".equalsIgnoreCase(method) && SETTINGS_PATH.matcher(path).matches()) {
            return new RateLimitRule(ZONE_PREFIX + "SETTINGS_PUT", SETTINGS_PUT_RATE_PER_HOUR, WINDOW_HOUR);
        }
        return null;
    }
}
