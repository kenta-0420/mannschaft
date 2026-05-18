package com.mannschaft.app.team.filter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.util.SessionHashUtil;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
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
 * F15.4: チーム公開系エンドポイントのレート制限フィルタ。
 *
 * <p>設計書: {@code docs/features/F15.4_team_store_search_within_org.md} §3.5 / §6 / §6.6
 *      および {@code docs/features/F15.4_phase5_team_public_detail.md} §4.4
 *
 * <p>対象エンドポイント（いずれも permitAll）:
 * <ul>
 *   <li>{@code GET /api/v1/organizations/{orgId}/teams/search}（Phase 1）</li>
 *   <li>{@code GET /api/v1/public/teams/{id}}（Phase 5-α）</li>
 * </ul>
 *
 * <p>レート上限（パスごとに独立したバケットを持つ）:
 * <table>
 *   <tr><th>パス</th><th>未ログイン</th><th>ログイン</th></tr>
 *   <tr><td>{@code /search}</td><td>30 req/min/IP</td><td>120 req/min/userId</td></tr>
 *   <tr><td>{@code /public/teams/*}</td><td>60 req/min/IP</td><td>200 req/min/userId</td></tr>
 * </table>
 * 詳細パス側を緩めるのは、ページ滞在中に複数 API（地図 / OGP プリフェッチ等）を
 * 連続で叩く正当な利用を許容するため（設計書 §4.4）。
 *
 * <p>キャッシュ戦略: Caffeine の {@code expireAfterAccess=2 時間} + {@code maximumSize=10_000}。
 * 構造は {@code PointCardRateLimitFilter} と同形。
 *
 * <p>§6.6 監査ログ: レート違反時のみ AuditEvent を非同期で記録する。
 * 検索側は {@link AuditEventType#TEAM_SEARCH_RATE_LIMITED}、
 * 詳細側は {@link AuditEventType#PUBLIC_TEAM_DETAIL_RATE_LIMIT_EXCEEDED}。
 * IP は生のまま保存せず SHA-256 ハッシュ化した上で metadata に格納する（PII 保護）。
 *
 * <p>※ Phase 1 まではクラス名 {@code OrganizationTeamSearchRateLimitFilter} だったが
 * Phase 5-α で店舗詳細パスも追加したため {@code PublicTeamApiRateLimitFilter} にリネームした。
 */
@Component
public class PublicTeamApiRateLimitFilter extends OncePerRequestFilter {

    // ──── レート定義 ─────────────────────────────
    /** 検索エンドポイント（Phase 1）の認証済み上限。 */
    private static final int SEARCH_AUTHENTICATED_RATE_PER_MINUTE = 120;
    /** 検索エンドポイント（Phase 1）の未認証上限。 */
    private static final int SEARCH_ANONYMOUS_RATE_PER_MINUTE = 30;
    /** 詳細エンドポイント（Phase 5-α）の認証済み上限。 */
    private static final int DETAIL_AUTHENTICATED_RATE_PER_MINUTE = 200;
    /** 詳細エンドポイント（Phase 5-α）の未認証上限。 */
    private static final int DETAIL_ANONYMOUS_RATE_PER_MINUTE = 60;

    private static final Duration BUCKET_TTL = Duration.ofHours(2);
    private static final long MAX_BUCKETS = 10_000L;

    // ──── パスパターン ───────────────────────────
    /** {@code /api/v1/organizations/{orgId}/teams/search} の GET のみマッチ。orgId を capture する。 */
    private static final Pattern ORG_TEAM_SEARCH_PATH =
            Pattern.compile("^/api/v1/organizations/([^/]+)/teams/search$");

    /** {@code /api/v1/public/teams/{id}} の GET のみマッチ。1 階層のみ（{@code /**} で再帰させない）。 */
    private static final Pattern PUBLIC_TEAM_DETAIL_PATH =
            Pattern.compile("^/api/v1/public/teams/([^/]+)$");

    /** レート制限の種別。バケット名前空間の隔離と監査ログ種別の分岐に用いる。 */
    private enum Target {
        /** 組織内チーム検索（Phase 1）。 */
        ORG_TEAM_SEARCH(SEARCH_AUTHENTICATED_RATE_PER_MINUTE, SEARCH_ANONYMOUS_RATE_PER_MINUTE,
                AuditEventType.TEAM_SEARCH_RATE_LIMITED),
        /** 店舗詳細公開取得（Phase 5-α）。 */
        PUBLIC_TEAM_DETAIL(DETAIL_AUTHENTICATED_RATE_PER_MINUTE, DETAIL_ANONYMOUS_RATE_PER_MINUTE,
                AuditEventType.PUBLIC_TEAM_DETAIL_RATE_LIMIT_EXCEEDED);

        final int authenticatedRate;
        final int anonymousRate;
        final AuditEventType auditEventType;

        Target(int authenticatedRate, int anonymousRate, AuditEventType auditEventType) {
            this.authenticatedRate = authenticatedRate;
            this.anonymousRate = anonymousRate;
            this.auditEventType = auditEventType;
        }
    }

    // ──── バケット ──────────────────────────────
    /** 認証済みユーザーのバケット（キー: {@code "<target>:u:" + userId}）。 */
    private final Cache<String, Bucket> authenticatedBuckets;
    /** 未認証アクセスのバケット（キー: {@code "<target>:ip:" + remoteAddr}）。 */
    private final Cache<String, Bucket> anonymousBuckets;

    /**
     * §6.6 監査ログ記録用（fire-and-forget）。
     *
     * <p>{@link ObjectProvider} 経由で弱結合化することで、{@code @WebMvcTest} ベースの
     * 最小コンテキストで {@code AuditLogService} の依存が解決できなくても本フィルタの
     * インスタンス化を阻害しない。
     */
    private final ObjectProvider<AuditLogService> auditLogServiceProvider;

    public PublicTeamApiRateLimitFilter(
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
                && !PUBLIC_TEAM_DETAIL_PATH.matcher(path).matches();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getServletPath();
        Target target;
        Matcher orgMatcher = ORG_TEAM_SEARCH_PATH.matcher(path);
        Matcher detailMatcher = PUBLIC_TEAM_DETAIL_PATH.matcher(path);
        if (orgMatcher.matches()) {
            target = Target.ORG_TEAM_SEARCH;
        } else if (detailMatcher.matches()) {
            target = Target.PUBLIC_TEAM_DETAIL;
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
            // §6.6: レート違反時のみ AuditEvent を記録（非同期 fire-and-forget）
            recordRateLimitAudit(request, target, userId, orgMatcher, detailMatcher);

            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", "60");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"error\":\"Too many requests\"}");
        }
    }

    /**
     * §6.6: レート制限違反を監査ログに記録する。
     *
     * <p>記録項目:
     * <ul>
     *   <li>eventType: 対象 API ごとに {@link Target#auditEventType}</li>
     *   <li>userId: 認証済みなら数値変換した userId、未認証は null</li>
     *   <li>organizationId / metadata: 検索 API は orgId / 詳細 API は teamId を metadata に格納
     *       （生 IP は保存せず SHA-256 ハッシュ化）</li>
     * </ul>
     */
    private void recordRateLimitAudit(HttpServletRequest request, Target target, Long userId,
                                      Matcher orgMatcher, Matcher detailMatcher) {
        String ipHash = SessionHashUtil.hash(request.getRemoteAddr());
        String metadata;
        Long organizationId = null;

        if (target == Target.ORG_TEAM_SEARCH && orgMatcher.matches()) {
            String orgIdStr = orgMatcher.group(1);
            organizationId = parseLongOrNull(orgIdStr);
            metadata = buildMetadataJson("orgId", orgIdStr, ipHash);
        } else if (target == Target.PUBLIC_TEAM_DETAIL && detailMatcher.matches()) {
            String teamIdStr = detailMatcher.group(1);
            metadata = buildMetadataJson("teamId", teamIdStr, ipHash);
        } else {
            metadata = buildMetadataJson(null, null, ipHash);
        }

        final Long capturedUserId = userId;
        final Long capturedOrgId = organizationId;
        final String capturedMetadata = metadata;
        final AuditEventType eventType = target.auditEventType;
        auditLogServiceProvider.ifAvailable(svc -> svc.record(
                eventType.name(),
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

    private Bucket newBucketPerMinute(int capacity) {
        return Bucket.builder()
                .addLimit(Bandwidth.simple(capacity, Duration.ofMinutes(1)))
                .build();
    }
}
