package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.common.ratelimit.RateLimitResult;
import com.mannschaft.app.common.ratelimit.ValkeyRateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * PR5 Portal の回数制限 port 実装（AC-71: scope ごと 10 回/時。正本 §370）。
 *
 * <p><b>フィルタ型（{@code AbstractRateLimitFilter}）を使わない理由</b>: 正本が定める制限主体は
 * <b>要求本文で指定される scope</b> であり、認証主体（userId / IP）ではない。フィルタ層では
 * 本文を読まずに scope を決められないため（本文を読むと後段の {@code @RequestBody} が壊れる）、
 * 制限は application service の中で {@link ValkeyRateLimiter} を直接呼んで掛ける。
 * カウンタ実装そのものは共通基盤（Valkey 固定ウィンドウ・fail-open）をそのまま使う。</p>
 *
 * <p>zone は他フィルタと衝突しない一意名（{@value #ZONE}）とする。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
class BillingCustomerPortalRateLimiterAdapter implements BillingCustomerPortalRateLimiter {

    /** Valkey キーの名前空間。エンドポイントと 1:1 に対応させる。 */
    static final String ZONE = "billing:portal-sessions";

    /** AC-71: 1 ウィンドウあたりの上限。10 回目までは成功し、11 回目が 429 になる。 */
    static final int LIMIT_PER_WINDOW = 10;

    /** AC-71: 窓は 1 時間。 */
    static final Duration WINDOW = Duration.ofHours(1);

    private final ValkeyRateLimiter rateLimiter;

    @Override
    public boolean tryConsume(EntitlementScopeKind scopeKind, long scopeId) {
        RateLimitResult result = rateLimiter.tryConsume(
                ZONE, scopeKind.name() + ":" + scopeId, LIMIT_PER_WINDOW, WINDOW);
        if (!result.allowed()) {
            log.warn("Portal セッション発行の回数制限に到達: scopeKind={} scopeId={}", scopeKind, scopeId);
        }
        return result.allowed();
    }
}
