package com.mannschaft.app.publicview.filter;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.ratelimit.AbstractRateLimitFilter;
import com.mannschaft.app.common.ratelimit.RateLimitResult;
import com.mannschaft.app.common.ratelimit.RateLimitRule;
import com.mannschaft.app.common.ratelimit.ValkeyRateLimiter;
import com.mannschaft.app.common.util.SessionHashUtil;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * F19.1: 公開ページ API（{@code /api/v1/public/(teams|organizations)/...}）+
 * F15.4 Phase 1 組織内チーム検索 API のレート制限フィルタ。
 *
 * <p>設計書: {@code docs/features/F19.1_public_pages_identity_disclosure.md} §10.2 / §17.8
 *      および {@code docs/features/F15.4_team_store_search_within_org.md} §3.5 / §6 / §6.6。</p>
 *
 * <p><strong>F15.4 統合戦略「案 B」</strong>（F19.1 §17.8）:
 * 本フィルタは F15.4 Phase 5-α 時点の {@code PublicTeamApiRateLimitFilter} を内包・リネーム・拡張したもの。
 * 対象パスを {@code teams} 単独から {@code (teams|organizations)} に拡張し、posts / events サブパスも
 * 同 Filter で扱う。レート上限値（60/min/IP・200/min/user）は §10.2 と整合（既存値そのまま）。</p>
 *
 * <p>対象エンドポイント（いずれも permitAll）:</p>
 * <ul>
 *   <li>{@code GET /api/v1/organizations/{orgId}/teams/search}（F15.4 Phase 1）</li>
 *   <li>{@code GET /api/v1/public/teams/{id}}（F15.4 Phase 5-α）</li>
 *   <li>{@code GET /api/v1/public/teams/{id}/posts}（F19.1 Phase 1）</li>
 *   <li>{@code GET /api/v1/public/teams/{id}/posts/{postId}}（F19.1 Phase 1）</li>
 *   <li>{@code GET /api/v1/public/teams/{id}/events}（F19.1 Phase 4 で活性化）</li>
 *   <li>{@code GET /api/v1/public/organizations/{id}}（F19.1 Phase 1）</li>
 *   <li>{@code GET /api/v1/public/organizations/{id}/posts}（F19.1 Phase 1）</li>
 *   <li>{@code GET /api/v1/public/organizations/{id}/posts/{postId}}（F19.1 Phase 1）</li>
 *   <li>{@code GET /api/v1/public/organizations/{id}/events}（F19.1 Phase 4 で活性化）</li>
 *   <li>{@code GET /api/v1/public/activities/{id}}（F06.4 公開活動記録・ID 直引き）</li>
 *   <li>{@code GET /api/v1/public/teams/{id}/activities}（F06.4）</li>
 *   <li>{@code GET /api/v1/public/teams/{id}/activities/{activityId}}（F06.4）</li>
 *   <li>{@code GET /api/v1/public/organizations/{id}/activities}（F06.4）</li>
 *   <li>{@code GET /api/v1/public/organizations/{id}/activities/{activityId}}（F06.4）</li>
 * </ul>
 *
 * <p>レート上限（パス分類ごとに独立した zone を持つ）:</p>
 * <table>
 *   <tr><th>パス</th><th>未ログイン</th><th>ログイン</th></tr>
 *   <tr><td>{@code /search}（F15.4 Phase 1）</td><td>30 req/min/IP</td><td>120 req/min/userId</td></tr>
 *   <tr><td>{@code /public/(teams|organizations)/...}</td><td>60 req/min/IP</td><td>200 req/min/userId</td></tr>
 * </table>
 *
 * <p><b>Valkey 化（Phase 2 第一陣）</b>: 旧実装の Bucket4j + Caffeine（プロセス内カウント）は
 * ECS 複数タスク構成でタスク数に比例して実効上限が緩むため、
 * {@link ValkeyRateLimiter}（docs/security/06 §4.3）に移行した。
 * Target enum ごとの zone 分離が旧実装の「Target 名前空間付きバケットキー」に相当する。
 * 実カウント・§4.3 標準ヘッダー・429 応答は {@link AbstractRateLimitFilter} が担う。</p>
 *
 * <p>監査ログ: 検索側は {@link AuditEventType#TEAM_SEARCH_RATE_LIMITED}、公開系は
 * {@link AuditEventType#PUBLIC_API_RATE_LIMIT_EXCEEDED}（F19.1 で導入、F15.4 Phase 5-α の
 * {@link AuditEventType#PUBLIC_TEAM_DETAIL_RATE_LIMIT_EXCEEDED} を上位互換として置換）を使用する。
 * 既存テストでは引き続き {@code PUBLIC_TEAM_DETAIL_RATE_LIMIT_EXCEEDED} を期待する箇所があり、
 * 統合 PR-4（PublicTeamController 改修）で AuditEventType の最終切替を行う。本 Filter は
 * 統合期間中の互換性のため、teams 単独詳細パス（{@code /api/v1/public/teams/<id>}、posts/events 無し）の
 * 監査ログ種別を {@link AuditEventType#PUBLIC_TEAM_DETAIL_RATE_LIMIT_EXCEEDED} で維持し、それ以外（posts/events
 * を含む新規パス、および organizations 系）は {@link AuditEventType#PUBLIC_API_RATE_LIMIT_EXCEEDED} を使う。</p>
 */
@Component
public class PublicApiRateLimitFilter extends AbstractRateLimitFilter {

    // ──── レート定義 ─────────────────────────────
    /** 検索エンドポイント（F15.4 Phase 1）の認証済み上限。 */
    private static final int SEARCH_AUTHENTICATED_RATE_PER_MINUTE = 120;
    /** 検索エンドポイント（F15.4 Phase 1）の未認証上限。 */
    private static final int SEARCH_ANONYMOUS_RATE_PER_MINUTE = 30;
    /** 公開ページ API の認証済み上限（F15.4 Phase 5-α / F19.1 共通）。 */
    private static final int PUBLIC_AUTHENTICATED_RATE_PER_MINUTE = 200;
    /** 公開ページ API の未認証上限（F15.4 Phase 5-α / F19.1 共通）。 */
    private static final int PUBLIC_ANONYMOUS_RATE_PER_MINUTE = 60;

    // 公開網漏れ是正: 大会・低リスク・Webhook 系の上限
    /** 大会一覧・詳細・フォルダ系の認証済み上限（PUBLIC_API と同値・別 zone）。 */
    private static final int TOURNAMENT_LIST_AUTHENTICATED_RATE_PER_MINUTE = 200;
    /** 大会一覧・詳細・フォルダ系の未認証上限（PUBLIC_API と同値・別 zone）。 */
    private static final int TOURNAMENT_LIST_ANONYMOUS_RATE_PER_MINUTE = 60;
    /** 大会の重い集計エンドポイント（順位表・マトリクス・ランキング・組み合わせ表）の認証済み上限。 */
    private static final int TOURNAMENT_AGGREGATE_AUTHENTICATED_RATE_PER_MINUTE = 80;
    /** 大会の重い集計エンドポイントの未認証上限（DB 負荷対策のため PUBLIC_API より厳しい）。 */
    private static final int TOURNAMENT_AGGREGATE_ANONYMOUS_RATE_PER_MINUTE = 20;
    /** 低リスク静的・準静的系（contact-invite / stats / postal-code / active-incidents）の認証済み上限。 */
    private static final int MISC_LOW_AUTHENTICATED_RATE_PER_MINUTE = 120;
    /** 低リスク静的・準静的系の未認証上限。 */
    private static final int MISC_LOW_ANONYMOUS_RATE_PER_MINUTE = 30;
    /** 署名検証済み Webhook 系の上限（検証コスト消費の連打を避けるが緩め）。 */
    private static final int WEBHOOK_RATE_PER_MINUTE = 120;

    /** ウィンドウ長（旧 Bucket4j intervally refill と同じ 1 分）。 */
    private static final Duration WINDOW = Duration.ofMinutes(1);

    /** Valkey zone 接頭辞。Target 名と組み合わせてバケット名前空間を一意化する。 */
    private static final String ZONE_PREFIX = "public-api:";

    // ──── パスパターン ───────────────────────────
    /** {@code /api/v1/organizations/{orgId}/teams/search} の GET のみマッチ。orgId を capture する。 */
    private static final Pattern ORG_TEAM_SEARCH_PATH =
            Pattern.compile("^/api/v1/organizations/([^/]+)/teams/search$");

    /**
     * F19.1 §17.8: 公開ページ API パスパターン。{@code teams} / {@code organizations}、
     * 単独詳細 / {@code /posts} / {@code /posts/{postId}} / {@code /events} を包含する。
     * {@code /**}（再帰）は使わず、各階層を {@code [^/]+} で 1 階層ずつ捕捉する（IDOR 防止）。
     *
     * <p>capture グループ:
     * <ol>
     *   <li>scopeType: {@code teams} or {@code organizations}</li>
     *   <li>scopeId: 数値 ID 文字列</li>
     *   <li>サブパス: {@code /posts} / {@code /posts/<id>} / {@code /events}（or 空）</li>
     * </ol>
     */
    // F21.1 §5.5: 公開FAQ（/faqs）をレート制限対象に追加（PUBLIC_API バケットを共有）。
    // F01.2 §5.9.5: slug 解決 `/api/v1/public/(teams|organizations)/slug-resolve` も
    //   この `([^/]+)` グループ（slug-resolve）にマッチするため PUBLIC_API バケットで自動的にレート制限される。
    // F06.4: 公開活動記録（/activities・/activities/{id}）をレート制限対象に追加（PUBLIC_API バケットを共有）。
    //   未認証で ID 総当りが可能な経路のため、他の公開系と同じ 60/min/IP で列挙攻撃を抑止する。
    //   ※ 既存 capture グループ 1〜3 の番号を変えないよう、追加は<b>グループ 3 の選択肢の中</b>に入れる
    //     （recordRateLimitAudit が group(1)/(2)/(3) を参照している）。
    // 公開網漏れ是正: F19.1 Phase 7 タイムライン投稿（/timeline-posts）を group 3 の選択肢に追加。
    //   SecurityConfig のコメントは「レート制限あり」を謳っていたが、本 Pattern に
    //   timeline-posts の選択肢が無かったため実際にはマッチせず素通りしていた（コメントと実装の不一致）。
    //   ※ 既存 capture グループ 1〜3 の番号は変えない（recordRateLimitAudit が参照している）。
    private static final Pattern PUBLIC_API_PATH =
            Pattern.compile("^/api/v1/public/(teams|organizations)/([^/]+)"
                    + "(/posts(/[^/]+)?|/events|/faqs|/activities(/[^/]+)?|/timeline-posts)?$");

    /**
     * 公開網漏れ是正: 公開ユーザープロフィール API（{@code GET /api/v1/public/users/{id}}・
     * {@code GET /api/v1/public/users/{id}/posts}）。{@link #PUBLIC_API_PATH} は
     * {@code (teams|organizations)} プレフィックス限定のため users 系はそもそもマッチしていなかった
     * （SecurityConfig のコメントは「レート制限あり」を謳っていたが実体が無かった）。
     * PUBLIC_API バケットを共有する。
     */
    private static final Pattern PUBLIC_USER_PATH =
            Pattern.compile("^/api/v1/public/users/([^/]+)(/posts)?$");

    /**
     * 公開網漏れ是正: 公開投稿コメント一覧（{@code GET /api/v1/public/blog-posts/{id}/comments}）。
     * {@link #PUBLIC_API_PATH} のプレフィックス外のため未マッチだった。PUBLIC_API バケットを共有する。
     */
    private static final Pattern PUBLIC_BLOG_COMMENTS_PATH =
            Pattern.compile("^/api/v1/public/blog-posts/([^/]+)/comments$");

    /**
     * F06.4: スコープ非依存の公開活動記録 単票パス
     * （{@code GET /api/v1/public/activities/{id}}）をマッチする。
     *
     * <p>SNS シェア用の ID 直引き経路。未認証で ID を総当りできるため PUBLIC_API バケット
     * （60/min/IP・200/min/user）を共有してレート制限する。
     * {@code /api/v1/public/activities}（末尾 ID なし）はエンドポイントが存在しないため
     * {@code ([^/]+)} 必須としてマッチさせない。</p>
     */
    private static final Pattern PUBLIC_ACTIVITY_BY_ID_PATH =
            Pattern.compile("^/api/v1/public/activities/([^/]+)$");

    /**
     * F19.1 Phase 4: 公開検索 API パスパターン。
     * {@code GET /api/v1/public/(teams|organizations)/search} をマッチする。
     *
     * <p>capture グループ:
     * <ol>
     *   <li>scopeType: {@code teams} or {@code organizations}</li>
     * </ol>
     */
    private static final Pattern PUBLIC_SEARCH_PATH =
            Pattern.compile("^/api/v1/public/(teams|organizations)/search$");

    /**
     * F22.1 §1.4: 市（Market）公開閲覧 API パスパターン。
     * {@code /api/v1/public/market/(listings|listings/{id}|regions|summary|categories)} をマッチする。
     * PUBLIC_API バケット（60/min/IP・200/min/user）を共有する。
     */
    private static final Pattern MARKET_API_PATH =
            Pattern.compile("^/api/v1/public/market/(listings(/[^/]+)?|regions|summary|categories)$");

    /**
     * F05.5 PR-D: 公開ファイルリンク（未認証可）POST 経路のパスパターン。
     * {@code POST /api/v1/public/file-links/{token}/(access|download-url)} をマッチする。
     *
     * <p>公開リンクは未認証で開けるため、トークン総当り（存在するトークンを探す列挙攻撃）を
     * レート制限で抑止する。PUBLIC_API バケット（60/min/IP・200/min/user）を共有する。
     * 他の公開 API と異なり本経路は <b>POST</b> のため、{@link #shouldNotFilter} で POST を許可する。</p>
     */
    private static final Pattern PUBLIC_FILE_LINKS_PATH =
            Pattern.compile("^/api/v1/public/file-links/([^/]+)/(access|download-url)$");

    /** F15.4 Phase 5-α 互換: 単独詳細パスのみマッチ（PUBLIC_TEAM_DETAIL_RATE_LIMIT_EXCEEDED 維持用）。 */
    private static final Pattern PUBLIC_TEAM_DETAIL_PATH =
            Pattern.compile("^/api/v1/public/teams/([^/]+)$");

    // ──── 公開網漏れ是正（大会・フォルダ系） ───────────────────
    /**
     * 公開大会 一覧 / 詳細（{@code GET /api/v1/public/organizations/{id}/tournaments}・
     * {@code .../tournaments/{id}}）とファイル置き場フォルダ一覧
     * （{@code GET /api/v1/tournaments/{id}/folders}・{@code .../divisions/{id}/folders}）。
     * IDOR 防止のため各階層を {@code [^/]+} で 1 階層ずつ捕捉する（{@code /**} 再帰は使わない）。
     * PUBLIC_API と同じ上限（60/min/IP・200/min/user）だが、大会 ID 総当りの影響範囲を隔離するため
     * 別 zone（TOURNAMENT_LIST）とする。
     */
    private static final Pattern TOURNAMENT_LIST_PATH =
            Pattern.compile("^/api/v1/public/organizations/([^/]+)/tournaments(/[^/]+)?$");

    private static final Pattern TOURNAMENT_FOLDERS_PATH =
            Pattern.compile("^/api/v1/tournaments/([^/]+)/(divisions/([^/]+)/)?folders$");

    /**
     * 公開大会の重い集計エンドポイント（順位表 / マトリクス / ランキング / 組み合わせ表）。
     * 未認証で連打されると DB 負荷が大きいため、PUBLIC_API より厳しい独立 zone
     * （TOURNAMENT_AGGREGATE・20/min/IP・80/min/user）で守る。
     */
    private static final Pattern TOURNAMENT_AGGREGATE_PATH = Pattern.compile(
            "^/api/v1/public/organizations/([^/]+)/tournaments/([^/]+)/"
                    + "(divisions/([^/]+)/(standings|matrix)|rankings/([^/]+)|bracket)$");

    /** 埋め込みウィジェット版の重い集計エンドポイント。TOURNAMENT_AGGREGATE zone を共有する。 */
    private static final Pattern EMBED_AGGREGATE_PATH = Pattern.compile(
            "^/api/v1/embed/organizations/([^/]+)/tournaments/([^/]+)/"
                    + "(standings/([^/]+)|bracket|rankings/([^/]+))$");

    // ──── 公開網漏れ是正（低リスク静的・準静的系） ─────────────
    /** 連絡先招待プレビュー（トークン総当り対策）。 */
    private static final Pattern CONTACT_INVITE_PATH =
            Pattern.compile("^/api/v1/contact-invite/([^/]+)$");

    /** ランディングページ公開統計（パラメータ無し・連打による DB 負荷対策）。 */
    private static final Pattern PUBLIC_STATS_PATH =
            Pattern.compile("^/api/v1/public/stats$");

    /** 郵便番号検証ポリシー（静的返却）。 */
    private static final Pattern POSTAL_CODE_POLICIES_PATH =
            Pattern.compile("^/api/v1/postal-code/policies$");

    /** アクティブ障害情報。 */
    private static final Pattern ACTIVE_INCIDENTS_PATH =
            Pattern.compile("^/api/v1/active-incidents$");

    // ──── 公開網漏れ是正（署名検証済み POST Webhook 系） ────────
    /**
     * 署名 / トークン検証を Controller 側で行う POST 系公開エンドポイント。
     * 検証コスト自体を消費させる連打を避けるため、緩め（120/min/IP）だが無制限にはしない zone
     * （WEBHOOK）で守る。この4パターンのみ {@link #shouldNotFilter} で POST を許可する。
     */
    private static final Pattern CSP_REPORTS_PATH =
            Pattern.compile("^/api/v1/security/csp-reports$");
    private static final Pattern GOOGLE_CALENDAR_WEBHOOK_PATH =
            Pattern.compile("^/api/v1/webhooks/google-calendar$");
    private static final Pattern SSR_LOGS_PATH =
            Pattern.compile("^/api/internal/ssr-logs$");
    private static final Pattern STRIPE_WEBHOOK_PATH =
            Pattern.compile("^/api/v1/webhooks/stripe(/[^/]+)?$");
    private static final Pattern LINE_WEBHOOK_PATH =
            Pattern.compile("^/api/v1/line/webhook/([^/]+)$");
    /**
     * Incoming Webhook 受信口（{@code POST /incoming/{token}}）。
     *
     * <p>ハンドラは {@code IncomingWebhookController#processIncoming} に実在する
     * （{@code @AuthorizedInService} 付与・認可根治戦役 Wave5 監査済）。パスに含まれる
     * トークンのみで認証するため、<b>トークン総当りの抑止が要る</b>のでレート制限対象に含める。</p>
     */
    private static final Pattern INCOMING_WEBHOOK_PATH =
            Pattern.compile("^/incoming/([^/]+)$");

    /** レート制限の種別。zone 名前空間の隔離と監査ログ種別の分岐に用いる。 */
    private enum Target {
        /** 組織内チーム検索（F15.4 Phase 1）。 */
        ORG_TEAM_SEARCH(SEARCH_AUTHENTICATED_RATE_PER_MINUTE, SEARCH_ANONYMOUS_RATE_PER_MINUTE),
        /** 公開ページ API 全般（F15.4 Phase 5-α + F19.1 拡張）。 */
        PUBLIC_API(PUBLIC_AUTHENTICATED_RATE_PER_MINUTE, PUBLIC_ANONYMOUS_RATE_PER_MINUTE),
        /** 公開大会 一覧・詳細・フォルダ系。 */
        TOURNAMENT_LIST(TOURNAMENT_LIST_AUTHENTICATED_RATE_PER_MINUTE, TOURNAMENT_LIST_ANONYMOUS_RATE_PER_MINUTE),
        /** 公開大会 重い集計系（順位表・マトリクス・ランキング・組み合わせ表・埋め込み版）。 */
        TOURNAMENT_AGGREGATE(TOURNAMENT_AGGREGATE_AUTHENTICATED_RATE_PER_MINUTE,
                TOURNAMENT_AGGREGATE_ANONYMOUS_RATE_PER_MINUTE),
        /** 低リスク静的・準静的系（contact-invite / stats / postal-code / active-incidents）。 */
        MISC_LOW(MISC_LOW_AUTHENTICATED_RATE_PER_MINUTE, MISC_LOW_ANONYMOUS_RATE_PER_MINUTE),
        /** 署名検証済み POST Webhook 系。 */
        WEBHOOK(WEBHOOK_RATE_PER_MINUTE, WEBHOOK_RATE_PER_MINUTE);

        final int authenticatedRate;
        final int anonymousRate;

        Target(int authenticatedRate, int anonymousRate) {
            this.authenticatedRate = authenticatedRate;
            this.anonymousRate = anonymousRate;
        }
    }

    /**
     * 監査ログ記録用（fire-and-forget）。
     *
     * <p>{@link ObjectProvider} 経由で弱結合化することで、{@code @WebMvcTest} ベースの
     * 最小コンテキストで {@code AuditLogService} の依存が解決できなくても本フィルタの
     * インスタンス化を阻害しない。</p>
     */
    private final ObjectProvider<AuditLogService> auditLogServiceProvider;

    /**
     * F19.1 Phase 5: Micrometer メトリクス記録用。
     *
     * <p>{@link ObjectProvider} 経由で弱結合化し、{@code @WebMvcTest} 最小コンテキストでも
     * インスタンス化できるようにする。</p>
     */
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    public PublicApiRateLimitFilter(
            ObjectProvider<ValkeyRateLimiter> rateLimiterProvider,
            ObjectProvider<AuditLogService> auditLogServiceProvider,
            ObjectProvider<MeterRegistry> meterRegistryProvider) {
        super(rateLimiterProvider);
        this.auditLogServiceProvider = auditLogServiceProvider;
        this.meterRegistryProvider = meterRegistryProvider;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        // F05.5 PR-D: 公開ファイルリンクは POST 経路のためレート制限対象に含める（トークン総当り防止）。
        if (PUBLIC_FILE_LINKS_PATH.matcher(path).matches()) {
            return !"POST".equalsIgnoreCase(request.getMethod());
        }
        // 公開網漏れ是正: 署名検証済み Webhook 系も POST 経路のためレート制限対象に含める。
        if (isWebhookPath(path)) {
            return !"POST".equalsIgnoreCase(request.getMethod());
        }
        // それ以外の公開 API は GET のみ対象。
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        return resolveTarget(path) == null;
    }

    /** WEBHOOK zone に属する POST 系パスか判定する。 */
    private static boolean isWebhookPath(String path) {
        return CSP_REPORTS_PATH.matcher(path).matches()
                || GOOGLE_CALENDAR_WEBHOOK_PATH.matcher(path).matches()
                || SSR_LOGS_PATH.matcher(path).matches()
                || STRIPE_WEBHOOK_PATH.matcher(path).matches()
                || LINE_WEBHOOK_PATH.matcher(path).matches()
                || INCOMING_WEBHOOK_PATH.matcher(path).matches();
    }

    @Override
    protected RateLimitRule resolveRule(HttpServletRequest request) {
        Target target = resolveTarget(request.getServletPath());
        if (target == null) {
            return null;
        }
        int limit = isAuthenticated() ? target.authenticatedRate : target.anonymousRate;
        return new RateLimitRule(ZONE_PREFIX + target.name(), limit, WINDOW);
    }

    /** パスから Target 種別を解決する。対象外なら null。 */
    private static Target resolveTarget(String path) {
        if (ORG_TEAM_SEARCH_PATH.matcher(path).matches()) {
            return Target.ORG_TEAM_SEARCH;
        }
        if (PUBLIC_API_PATH.matcher(path).matches()
                || PUBLIC_SEARCH_PATH.matcher(path).matches()
                || PUBLIC_ACTIVITY_BY_ID_PATH.matcher(path).matches()
                || MARKET_API_PATH.matcher(path).matches()
                || PUBLIC_FILE_LINKS_PATH.matcher(path).matches()
                || PUBLIC_USER_PATH.matcher(path).matches()
                || PUBLIC_BLOG_COMMENTS_PATH.matcher(path).matches()) {
            return Target.PUBLIC_API;
        }
        if (TOURNAMENT_LIST_PATH.matcher(path).matches()
                || TOURNAMENT_FOLDERS_PATH.matcher(path).matches()) {
            return Target.TOURNAMENT_LIST;
        }
        if (TOURNAMENT_AGGREGATE_PATH.matcher(path).matches()
                || EMBED_AGGREGATE_PATH.matcher(path).matches()) {
            return Target.TOURNAMENT_AGGREGATE;
        }
        if (CONTACT_INVITE_PATH.matcher(path).matches()
                || PUBLIC_STATS_PATH.matcher(path).matches()
                || POSTAL_CODE_POLICIES_PATH.matcher(path).matches()
                || ACTIVE_INCIDENTS_PATH.matcher(path).matches()) {
            return Target.MISC_LOW;
        }
        if (isWebhookPath(path)) {
            return Target.WEBHOOK;
        }
        return null;
    }

    /** F19.1 Phase 5: リクエスト通過時にリクエストカウンターを記録する。 */
    @Override
    protected void onRequestPassed(HttpServletRequest request, HttpServletResponse response) {
        recordRequestMetric(request.getServletPath(), request.getMethod(), response.getStatus());
    }

    /** レート超過時: 監査ログ（fire-and-forget）+ 超過メトリクスを記録する。 */
    @Override
    protected void onRateLimitExceeded(HttpServletRequest request, RateLimitResult result) {
        Target target = resolveTarget(request.getServletPath());
        Long userId = null;
        if (isAuthenticated()) {
            Authentication auth = currentAuthentication();
            userId = parseUserIdOrNull(auth.getName());
        }
        Matcher orgMatcher = ORG_TEAM_SEARCH_PATH.matcher(request.getServletPath());
        Matcher publicMatcher = PUBLIC_API_PATH.matcher(request.getServletPath());
        Matcher activityMatcher = PUBLIC_ACTIVITY_BY_ID_PATH.matcher(request.getServletPath());
        recordRateLimitAudit(request, target, userId, orgMatcher, publicMatcher, activityMatcher);

        // F19.1 Phase 5: レート超過カウンター記録
        recordRateLimitExceededMetric(request.getServletPath(), request.getRemoteAddr());
    }

    /**
     * F19.1 Phase 5: 公開 API へのリクエストを Counter に記録する。
     *
     * <p>path の {@code {id}} 部分（数値 or UUID）を {@code *} に正規化して
     * cardinality 爆発を防ぐ。</p>
     *
     * @param rawPath    リクエストパス（正規化前）
     * @param method     HTTP メソッド
     * @param statusCode HTTP レスポンスステータスコード
     */
    private void recordRequestMetric(String rawPath, String method, int statusCode) {
        meterRegistryProvider.ifAvailable(registry -> {
            String normalizedPath = normalizePath(rawPath);
            Counter.builder("public.api.requests")
                    .description("公開ページ API へのリクエスト数")
                    .tag("path", normalizedPath)
                    .tag("method", method)
                    .tag("status", String.valueOf(statusCode))
                    .register(registry)
                    .increment();
        });
    }

    /**
     * F19.1 Phase 5: レート制限超過を Counter に記録する。
     *
     * <p>IP アドレスは SHA-256 先頭 8 文字のハッシュ値に変換してから tag に格納する
     * （生 IP の記録を避けて GDPR リスクを低減する）。</p>
     *
     * @param rawPath    リクエストパス（正規化前）
     * @param remoteAddr クライアント IP アドレス
     */
    private void recordRateLimitExceededMetric(String rawPath, String remoteAddr) {
        meterRegistryProvider.ifAvailable(registry -> {
            String normalizedPath = normalizePath(rawPath);
            String ipHash = sha256Prefix8(remoteAddr);
            Counter.builder("public.api.rate_limit.exceeded")
                    .description("公開ページ API のレート制限超過回数")
                    .tag("path", normalizedPath)
                    .tag("ip_hash", ipHash)
                    .register(registry)
                    .increment();
        });
    }

    /**
     * パスの数値 ID 部分を {@code *} に正規化する（cardinality 爆発防止）。
     *
     * <p>例:</p>
     * <ul>
     *   <li>/api/v1/public/teams/123/posts -&gt; /api/v1/public/teams/{@literal *}/posts</li>
     *   <li>/api/v1/public/teams/123/posts/456 -&gt; /api/v1/public/teams/{@literal *}/posts/{@literal *}</li>
     *   <li>/api/v1/organizations/789/teams/search -&gt; /api/v1/organizations/{@literal *}/teams/search</li>
     * </ul>
     *
     * @param path 生パス
     * @return 正規化後パス
     */
    static String normalizePath(String path) {
        if (path == null) {
            return "*";
        }
        // F05.5 PR-D: 公開ファイルリンクのトークン（UUID）を * に正規化（cardinality 爆発防止）。
        // 数値正規化より先に行う（トークンは非数値のため後段の数値置換では潰せない）。
        String normalized = path.replaceAll(
                "/api/v1/public/file-links/[^/]+/(access|download-url)",
                "/api/v1/public/file-links/*/$1");
        // 数値（Long range）を * に置換
        return normalized.replaceAll("/[0-9]+", "/*");
    }

    /**
     * 入力文字列の SHA-256 ハッシュ値の先頭 8 文字を返す。
     *
     * <p>IP アドレスを tag に格納する際の GDPR リスク低減目的で使用する。
     * 変換に失敗した場合は {@code "unknown"} を返す。</p>
     *
     * @param input ハッシュ対象の文字列
     * @return SHA-256 ハッシュ先頭 8 文字
     */
    static String sha256Prefix8(String input) {
        if (input == null) {
            return "unknown";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
                if (sb.length() >= 8) {
                    break;
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * レート制限違反を監査ログに記録する。
     *
     * <p>記録項目:</p>
     * <ul>
     *   <li>eventType: 検索系は {@link AuditEventType#TEAM_SEARCH_RATE_LIMITED}、
     *       teams 単独詳細（F15.4 Phase 5-α 互換パス）は
     *       {@link AuditEventType#PUBLIC_TEAM_DETAIL_RATE_LIMIT_EXCEEDED}、
     *       posts / events / organizations 系は
     *       {@link AuditEventType#PUBLIC_API_RATE_LIMIT_EXCEEDED}</li>
     *   <li>userId: 認証済みなら数値変換した userId、未認証は null</li>
     *   <li>organizationId / metadata: 検索 API は orgId / 詳細 API は teamId or orgId を metadata に格納
     *       （生 IP は保存せず SHA-256 ハッシュ化）</li>
     * </ul>
     *
     * <p><b>F06.4 ID 直引き経路の取りこぼし是正</b>: 本メソッドは当初
     * {@link #PUBLIC_API_PATH} にしかマッチしなかったため、スコープ非依存の
     * {@code GET /api/v1/public/activities/{id}} が最後の else に落ち、
     * {@code {"ipHash":"..."}} だけが残って<b>どの ID を総当りされたのかが記録されなかった</b>。
     * 活動記録 ID は {@code BIGINT AUTO_INCREMENT} の連番で最も列挙されやすい経路のため、
     * {@link #PUBLIC_ACTIVITY_BY_ID_PATH} も他の公開 EP と同形式（{@code activityId} キー）で
     * metadata に載せる。</p>
     */
    private void recordRateLimitAudit(HttpServletRequest request, Target target, Long userId,
                                      Matcher orgMatcher, Matcher publicMatcher,
                                      Matcher activityMatcher) {
        String ipHash = SessionHashUtil.hash(request.getRemoteAddr());
        String metadata;
        Long organizationId = null;
        AuditEventType eventType;
        Matcher searchMatcher = PUBLIC_SEARCH_PATH.matcher(request.getServletPath());

        if (target == Target.ORG_TEAM_SEARCH && orgMatcher.matches()) {
            String orgIdStr = orgMatcher.group(1);
            organizationId = parseLongOrNull(orgIdStr);
            metadata = buildMetadataJson("orgId", orgIdStr, ipHash);
            eventType = AuditEventType.TEAM_SEARCH_RATE_LIMITED;
        } else if (target == Target.PUBLIC_API && searchMatcher.matches()) {
            // F19.1 Phase 4 公開検索 API（ID なし）
            String scopeType = searchMatcher.group(1);
            metadata = buildMetadataJson("scopeType", scopeType, ipHash);
            eventType = AuditEventType.PUBLIC_API_RATE_LIMIT_EXCEEDED;
        } else if (target == Target.PUBLIC_API && publicMatcher.matches()) {
            String scopeType = publicMatcher.group(1);
            String scopeIdStr = publicMatcher.group(2);
            String subPath = publicMatcher.group(3);
            // F15.4 Phase 5-α 互換: teams 単独詳細（サブパスなし）のみ既存 eventType を維持
            boolean isTeamsSimpleDetail = "teams".equals(scopeType)
                    && (subPath == null || subPath.isEmpty())
                    && PUBLIC_TEAM_DETAIL_PATH.matcher(request.getServletPath()).matches();
            if (isTeamsSimpleDetail) {
                eventType = AuditEventType.PUBLIC_TEAM_DETAIL_RATE_LIMIT_EXCEEDED;
                metadata = buildMetadataJson("teamId", scopeIdStr, ipHash);
            } else {
                eventType = AuditEventType.PUBLIC_API_RATE_LIMIT_EXCEEDED;
                String keyName = "teams".equals(scopeType) ? "teamId" : "organizationId";
                metadata = buildMetadataJson(keyName, scopeIdStr, ipHash);
            }
        } else if (target == Target.PUBLIC_API && activityMatcher.matches()) {
            // F06.4: スコープ非依存の ID 直引き（GET /api/v1/public/activities/{id}）。
            // 連番 ID の総当りを事後追跡できるよう、叩かれた activityId を必ず残す。
            String activityIdStr = activityMatcher.group(1);
            metadata = buildMetadataJson("activityId", activityIdStr, ipHash);
            eventType = AuditEventType.PUBLIC_API_RATE_LIMIT_EXCEEDED;
        } else {
            metadata = buildMetadataJson(null, null, ipHash);
            eventType = AuditEventType.PUBLIC_API_RATE_LIMIT_EXCEEDED;
        }

        final Long capturedUserId = userId;
        final Long capturedOrgId = organizationId;
        final String capturedMetadata = metadata;
        final AuditEventType capturedEventType = eventType;
        auditLogServiceProvider.ifAvailable(svc -> svc.record(
                capturedEventType.name(),
                capturedUserId,
                null,
                null,
                capturedOrgId,
                null,
                null,
                null,
                capturedMetadata
        ));
    }

    /**
     * {@code {"<keyName>":"...","ipHash":"abc..."}} 形式の JSON を構築する。
     * keyName が null の場合は {@code {"ipHash":"..."}} のみ。
     */
    private String buildMetadataJson(String keyName, String keyValue, String ipHash) {
        StringBuilder sb = new StringBuilder("{");
        if (keyName != null) {
            sb.append("\"").append(keyName).append("\":");
            if (keyValue == null) {
                sb.append("null");
            } else {
                sb.append("\"").append(escapeJson(keyValue)).append("\"");
            }
            sb.append(",");
        }
        sb.append("\"ipHash\":");
        if (ipHash == null) {
            sb.append("null");
        } else {
            sb.append("\"").append(ipHash).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static Long parseLongOrNull(String s) {
        if (s == null) return null;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Long parseUserIdOrNull(String s) {
        return parseLongOrNull(s);
    }
}
