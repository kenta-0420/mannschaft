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
 * F15.4: 組織内チーム（店舗）検索エンドポイントのレート制限フィルタ。
 *
 * <p>設計書: {@code docs/features/F15.4_team_store_search_within_org.md} §3.5 / §6 / §6.6
 *
 * <p>対象エンドポイント: {@code GET /api/v1/organizations/{orgId}/teams/search}（permitAll）
 *
 * <p>レート上限:
 * <ul>
 *   <li>未ログイン: <strong>30 req / 分 / IP</strong></li>
 *   <li>ログイン: <strong>120 req / 分 / userId</strong></li>
 * </ul>
 *
 * <p>キャッシュ戦略: Caffeine の {@code expireAfterAccess=2 時間} + {@code maximumSize=10_000}。
 * 構造は {@code PointCardRateLimitFilter} と同形。
 *
 * <p>§6.6 監査ログ: レート制限違反時のみ {@link AuditEventType#TEAM_SEARCH_RATE_LIMITED} を
 * 非同期で記録する。通常時（200 応答）は記録しない（公開検索のため個人情報を含まない）。
 * IP は生のまま保存せず SHA-256 ハッシュ化した上で metadata に格納する（PII 保護）。
 */
@Component
public class OrganizationTeamSearchRateLimitFilter extends OncePerRequestFilter {

    // ──── レート定義 ─────────────────────────────
    private static final int AUTHENTICATED_RATE_PER_MINUTE = 120;
    private static final int ANONYMOUS_RATE_PER_MINUTE = 30;

    private static final Duration BUCKET_TTL = Duration.ofHours(2);
    private static final long MAX_BUCKETS = 10_000L;

    // ──── パスパターン ───────────────────────────
    /** {@code /api/v1/organizations/{orgId}/teams/search} の GET のみマッチ。orgId を capture する。 */
    private static final Pattern ORG_TEAM_SEARCH_PATH =
            Pattern.compile("^/api/v1/organizations/([^/]+)/teams/search$");

    // ──── バケット ──────────────────────────────
    /** 認証済みユーザーのバケット（キー: {@code "u:" + userId}）。 */
    private final Cache<String, Bucket> authenticatedBuckets;
    /** 未認証アクセスのバケット（キー: {@code "ip:" + remoteAddr}）。 */
    private final Cache<String, Bucket> anonymousBuckets;

    /**
     * §6.6 監査ログ記録用（fire-and-forget）。
     *
     * <p>{@link ObjectProvider} 経由で弱結合化することで、{@code @WebMvcTest} ベースの
     * 最小コンテキストで {@code AuditLogService} の依存が解決できなくても本フィルタの
     * インスタンス化を阻害しない。AuditLogService Bean が存在する本番／フル統合テスト
     * では通常通り {@link AuditLogService#record} が呼ばれる。
     */
    private final ObjectProvider<AuditLogService> auditLogServiceProvider;

    public OrganizationTeamSearchRateLimitFilter(
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
        return !ORG_TEAM_SEARCH_PATH.matcher(request.getServletPath()).matches();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean authenticated = auth != null
                && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getPrincipal());

        Bucket bucket;
        Long userId = null;
        if (authenticated) {
            String name = auth.getName();
            String key = "u:" + name;
            bucket = authenticatedBuckets.get(key,
                    k -> newBucketPerMinute(AUTHENTICATED_RATE_PER_MINUTE));
            userId = parseUserIdOrNull(name);
        } else {
            String key = "ip:" + request.getRemoteAddr();
            bucket = anonymousBuckets.get(key,
                    k -> newBucketPerMinute(ANONYMOUS_RATE_PER_MINUTE));
        }

        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
        } else {
            // §6.6: レート違反時のみ AuditEvent を記録（非同期 fire-and-forget）
            recordRateLimitAudit(request, userId);

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
     *   <li>eventType: {@code TEAM_SEARCH_RATE_LIMITED}</li>
     *   <li>userId: 認証済みなら数値変換した userId、未認証は null</li>
     *   <li>organizationId: URL から抽出（数値変換に失敗した場合は null）</li>
     *   <li>metadata: {@code {"orgId": "...", "ipHash": "<sha256>"}}（生 IP は保存しない）</li>
     * </ul>
     *
     * <p>検索キーワードは PII 漏洩懸念のため metadata に含めない（設計書 §6.8）。
     */
    private void recordRateLimitAudit(HttpServletRequest request, Long userId) {
        String path = request.getServletPath();
        Matcher matcher = ORG_TEAM_SEARCH_PATH.matcher(path);
        String orgIdStr = null;
        Long organizationId = null;
        if (matcher.matches()) {
            orgIdStr = matcher.group(1);
            organizationId = parseLongOrNull(orgIdStr);
        }

        String ipHash = SessionHashUtil.hash(request.getRemoteAddr());
        String metadata = buildMetadataJson(orgIdStr, ipHash);

        final Long capturedUserId = userId;
        final Long capturedOrgId = organizationId;
        auditLogServiceProvider.ifAvailable(svc -> svc.record(
                AuditEventType.TEAM_SEARCH_RATE_LIMITED.name(),
                capturedUserId,
                null,
                null,
                capturedOrgId,
                null,
                null,
                null,
                metadata
        ));
    }

    /** {@code {"orgId":"100","ipHash":"abc..."}} 形式の JSON を構築する。 */
    private String buildMetadataJson(String orgIdStr, String ipHash) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"orgId\":");
        if (orgIdStr == null) {
            sb.append("null");
        } else {
            sb.append("\"").append(escapeJson(orgIdStr)).append("\"");
        }
        sb.append(",\"ipHash\":");
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
