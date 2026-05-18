package com.mannschaft.app.publicview.filter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.util.SessionHashUtil;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 * </ul>
 *
 * <p>レート上限（パス分類ごとに独立したバケットを持つ）:</p>
 * <table>
 *   <tr><th>パス</th><th>未ログイン</th><th>ログイン</th></tr>
 *   <tr><td>{@code /search}（F15.4 Phase 1）</td><td>30 req/min/IP</td><td>120 req/min/userId</td></tr>
 *   <tr><td>{@code /public/(teams|organizations)/...}</td><td>60 req/min/IP</td><td>200 req/min/userId</td></tr>
 * </table>
 *
 * <p>キャッシュ戦略: Caffeine の {@code expireAfterAccess=2 時間} + {@code maximumSize=10_000}。</p>
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
public class PublicApiRateLimitFilter extends OncePerRequestFilter {

    // ──── レート定義 ─────────────────────────────
    /** 検索エンドポイント（F15.4 Phase 1）の認証済み上限。 */
    private static final int SEARCH_AUTHENTICATED_RATE_PER_MINUTE = 120;
    /** 検索エンドポイント（F15.4 Phase 1）の未認証上限。 */
    private static final int SEARCH_ANONYMOUS_RATE_PER_MINUTE = 30;
    /** 公開ページ API の認証済み上限（F15.4 Phase 5-α / F19.1 共通）。 */
    private static final int PUBLIC_AUTHENTICATED_RATE_PER_MINUTE = 200;
    /** 公開ページ API の未認証上限（F15.4 Phase 5-α / F19.1 共通）。 */
    private static final int PUBLIC_ANONYMOUS_RATE_PER_MINUTE = 60;

    private static final Duration BUCKET_TTL = Duration.ofHours(2);
    private static final long MAX_BUCKETS = 10_000L;

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
    private static final Pattern PUBLIC_API_PATH =
            Pattern.compile("^/api/v1/public/(teams|organizations)/([^/]+)(/posts(/[^/]+)?|/events)?$");

    /** F15.4 Phase 5-α 互換: 単独詳細パスのみマッチ（PUBLIC_TEAM_DETAIL_RATE_LIMIT_EXCEEDED 維持用）。 */
    private static final Pattern PUBLIC_TEAM_DETAIL_PATH =
            Pattern.compile("^/api/v1/public/teams/([^/]+)$");

    /** レート制限の種別。バケット名前空間の隔離と監査ログ種別の分岐に用いる。 */
    private enum Target {
        /** 組織内チーム検索（F15.4 Phase 1）。 */
        ORG_TEAM_SEARCH(SEARCH_AUTHENTICATED_RATE_PER_MINUTE, SEARCH_ANONYMOUS_RATE_PER_MINUTE),
        /** 公開ページ API 全般（F15.4 Phase 5-α + F19.1 拡張）。 */
        PUBLIC_API(PUBLIC_AUTHENTICATED_RATE_PER_MINUTE, PUBLIC_ANONYMOUS_RATE_PER_MINUTE);

        final int authenticatedRate;
        final int anonymousRate;

        Target(int authenticatedRate, int anonymousRate) {
            this.authenticatedRate = authenticatedRate;
            this.anonymousRate = anonymousRate;
        }
    }

    // ──── バケット ──────────────────────────────
    /** 認証済みユーザーのバケット（キー: {@code "<target>:u:" + userId}）。 */
    private final Cache<String, Bucket> authenticatedBuckets;
    /** 未認証アクセスのバケット（キー: {@code "<target>:ip:" + remoteAddr}）。 */
    private final Cache<String, Bucket> anonymousBuckets;

    /**
     * 監査ログ記録用（fire-and-forget）。
     *
     * <p>{@link ObjectProvider} 経由で弱結合化することで、{@code @WebMvcTest} ベースの
     * 最小コンテキストで {@code AuditLogService} の依存が解決できなくても本フィルタの
     * インスタンス化を阻害しない。</p>
     */
    private final ObjectProvider<AuditLogService> auditLogServiceProvider;

    public PublicApiRateLimitFilter(
            ObjectProvider<AuditLogService> auditLogServiceProvider) {
        this.authenticatedBuckets = newCache();
        this.anonymousBuckets = newCache();
        this.auditLogServiceProvider = auditLogServiceProvider;
    }

    private static Cache<String, Bucket> newCache() {
        return Caffeine.<String, Bucket>newBuilder()
                .expireAfterAccess(BUCKET_TTL)
                .maximumSize(MAX_BUCKETS)
                .build();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 対象パス以外は全てスキップ
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getServletPath();
        return !ORG_TEAM_SEARCH_PATH.matcher(path).matches()
                && !PUBLIC_API_PATH.matcher(path).matches();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getServletPath();
        Target target;
        Matcher orgMatcher = ORG_TEAM_SEARCH_PATH.matcher(path);
        Matcher publicMatcher = PUBLIC_API_PATH.matcher(path);
        if (orgMatcher.matches()) {
            target = Target.ORG_TEAM_SEARCH;
        } else if (publicMatcher.matches()) {
            target = Target.PUBLIC_API;
        } else {
            // shouldNotFilter で弾いているので通常到達しないが、念のため透過
            chain.doFilter(request, response);
            return;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean authenticated = auth != null
                && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getPrincipal());

        Bucket bucket;
        Long userId = null;
        if (authenticated) {
            String name = auth.getName();
            String key = target.name() + ":u:" + name;
            bucket = authenticatedBuckets.get(key,
                    k -> newBucketPerMinute(target.authenticatedRate));
            userId = parseUserIdOrNull(name);
        } else {
            String key = target.name() + ":ip:" + request.getRemoteAddr();
            bucket = anonymousBuckets.get(key,
                    k -> newBucketPerMinute(target.anonymousRate));
        }

        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
        } else {
            // レート違反時の AuditEvent 記録（非同期 fire-and-forget）
            recordRateLimitAudit(request, target, userId, orgMatcher, publicMatcher);

            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", "60");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"error\":\"Too many requests\"}");
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
     */
    private void recordRateLimitAudit(HttpServletRequest request, Target target, Long userId,
                                      Matcher orgMatcher, Matcher publicMatcher) {
        String ipHash = SessionHashUtil.hash(request.getRemoteAddr());
        String metadata;
        Long organizationId = null;
        AuditEventType eventType;

        if (target == Target.ORG_TEAM_SEARCH && orgMatcher.matches()) {
            String orgIdStr = orgMatcher.group(1);
            organizationId = parseLongOrNull(orgIdStr);
            metadata = buildMetadataJson("orgId", orgIdStr, ipHash);
            eventType = AuditEventType.TEAM_SEARCH_RATE_LIMITED;
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

    /**
     * 1 分間に {@code capacity} トークンが <strong>一括補充</strong>される Bucket を生成する
     * （{@link Refill#intervally(long, Duration) intervally refill}）。
     *
     * <p>{@code Bandwidth.simple} の greedy refill ではテスト実時間が長引くと連続的にトークンが
     * 補充されてしまい、60 件消費直後の 61 件目が 200 を返してしまう問題があるため、
     * F19.1 Phase 1 では intervally refill に統一した。挙動上は「1 分間に最大 capacity 件まで処理、
     * 1 分経過時点で一斉にリセット」となる。本番運用での体感差は許容範囲（設計書 §10.2 と整合）。</p>
     */
    private Bucket newBucketPerMinute(int capacity) {
        Bandwidth limit = Bandwidth.classic(
                capacity,
                Refill.intervally(capacity, Duration.ofMinutes(1)));
        return Bucket.builder()
                .addLimit(limit)
                .build();
    }
}
