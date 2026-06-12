package com.mannschaft.app.auth;

import com.mannschaft.app.common.ratelimit.AbstractRateLimitFilter;
import com.mannschaft.app.common.ratelimit.RateLimitRule;
import com.mannschaft.app.common.ratelimit.ValkeyRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * F10.3 監査ログ API のユーザー別レートリミットフィルタ。
 *
 * <p>以下のエンドポイントに対してレートリミットを適用する:</p>
 * <ul>
 *   <li>{@code GET /api/v1/admin/audit-logs}                  — 60 req/分（SYSTEM_ADMIN 向け）</li>
 *   <li>{@code GET /api/v1/users/me/audit-logs}               — 30 req/分（一般ユーザー向け）</li>
 *   <li>{@code GET /api/v1/teams/{teamId}/audit-logs}         — 30 req/分（チームADMIN向け）</li>
 *   <li>{@code GET /api/v1/organizations/{orgId}/audit-logs}  — 30 req/分（組織ADMIN向け）</li>
 * </ul>
 *
 * <p><b>Valkey 化（第二陣A）</b>: 旧実装の Bucket4j + Caffeine（プロセス内カウント）は
 * ECS 複数タスク構成でタスク数に比例して実効上限が緩むため、
 * {@link ValkeyRateLimiter}（docs/security/06 §4.3）に移行した。</p>
 */
@Component
public class AuditLogRateLimitFilter extends AbstractRateLimitFilter {

    /** エンドポイント別の設定 */
    private enum Endpoint {
        ADMIN_AUDIT_LOGS("/api/v1/admin/audit-logs", "GET", 60),
        MY_AUDIT_LOGS("/api/v1/users/me/audit-logs", "GET", 30),
        TEAM_AUDIT_LOGS("/api/v1/teams/*/audit-logs", "GET", 30),
        ORGANIZATION_AUDIT_LOGS("/api/v1/organizations/*/audit-logs", "GET", 30);

        final String path;
        final String method;
        final int capacityPerMinute;

        Endpoint(String path, String method, int capacityPerMinute) {
            this.path = path;
            this.method = method;
            this.capacityPerMinute = capacityPerMinute;
        }

        boolean matches(HttpServletRequest request) {
            if (!this.method.equalsIgnoreCase(request.getMethod())) return false;
            String servletPath = request.getServletPath();
            if (!this.path.contains("*")) {
                return this.path.equals(servletPath);
            }
            // ワイルドカード: prefix*suffix 形式のみサポート
            int starIdx = this.path.indexOf('*');
            String prefix = this.path.substring(0, starIdx);
            String suffix = this.path.substring(starIdx + 1);
            return servletPath.startsWith(prefix) && servletPath.endsWith(suffix)
                    && servletPath.length() > prefix.length() + suffix.length();
        }
    }

    /** ウィンドウ長（旧 Bucket4j Bandwidth と同じ 1 分）。 */
    private static final Duration WINDOW = Duration.ofMinutes(1);

    /** Valkey zone 接頭辞。 */
    private static final String ZONE_PREFIX = "audit-log:";

    public AuditLogRateLimitFilter(ObjectProvider<ValkeyRateLimiter> rateLimiterProvider) {
        super(rateLimiterProvider);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        for (Endpoint ep : Endpoint.values()) {
            if (ep.matches(request)) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected RateLimitRule resolveRule(HttpServletRequest request) {
        for (Endpoint ep : Endpoint.values()) {
            if (ep.matches(request)) {
                return new RateLimitRule(ZONE_PREFIX + ep.name(), ep.capacityPerMinute, WINDOW);
            }
        }
        return null;
    }
}
