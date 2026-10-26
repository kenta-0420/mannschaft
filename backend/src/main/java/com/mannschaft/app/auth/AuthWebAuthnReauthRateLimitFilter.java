package com.mannschaft.app.auth;

import com.mannschaft.app.common.ratelimit.AbstractRateLimitFilter;
import com.mannschaft.app.common.ratelimit.RateLimitRule;
import com.mannschaft.app.common.ratelimit.ValkeyRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * F18 提示モード追加保護用の WebAuthn 再認証エンドポイント専用レートリミットフィルタ。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §9.6
 *
 * <p>対象パス（各 10 req/分）:
 * <ul>
 *   <li>{@code POST /api/v1/auth/webauthn/reauthenticate-begin}</li>
 *   <li>{@code POST /api/v1/auth/webauthn/reauthenticate-complete}</li>
 * </ul>
 *
 * <p>※ ログイン用 {@code /login/begin} / {@code /login/complete} には影響しない。
 * 提示モード追加保護に限った独立フィルタ。
 *
 * <p><b>Valkey 化（第二陣A）</b>: 旧実装の Bucket4j + Caffeine（プロセス内カウント）は
 * ECS 複数タスク構成でタスク数に比例して実効上限が緩むため、
 * {@link ValkeyRateLimiter}（docs/security/06 §4.3）に移行した。</p>
 */
@Component
public class AuthWebAuthnReauthRateLimitFilter extends AbstractRateLimitFilter {

    private static final String REAUTH_BEGIN_PATH = "/api/v1/auth/webauthn/reauthenticate-begin";
    private static final String REAUTH_COMPLETE_PATH = "/api/v1/auth/webauthn/reauthenticate-complete";
    private static final int RATE_PER_MINUTE = 10;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    /** Valkey zone 接頭辞。 */
    private static final String ZONE_PREFIX = "webauthn-reauth:";

    public AuthWebAuthnReauthRateLimitFilter(ObjectProvider<ValkeyRateLimiter> rateLimiterProvider) {
        super(rateLimiterProvider);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getServletPath();
        return !(REAUTH_BEGIN_PATH.equals(path) || REAUTH_COMPLETE_PATH.equals(path));
    }

    @Override
    protected RateLimitRule resolveRule(HttpServletRequest request) {
        String path = request.getServletPath();
        if (REAUTH_BEGIN_PATH.equals(path)) {
            return new RateLimitRule(ZONE_PREFIX + "BEGIN", RATE_PER_MINUTE, WINDOW);
        }
        if (REAUTH_COMPLETE_PATH.equals(path)) {
            return new RateLimitRule(ZONE_PREFIX + "COMPLETE", RATE_PER_MINUTE, WINDOW);
        }
        return null;
    }
}
