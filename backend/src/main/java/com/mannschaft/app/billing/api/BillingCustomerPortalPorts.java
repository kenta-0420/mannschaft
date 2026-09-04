package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.EntitlementScopeKind;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * PR5 Billing Center: Stripe Customer Portal セッション発行（AC-61〜AC-74）の application service 境界。
 *
 * <p>PR4 checkout（{@code BillingCheckoutPorts}）と同じ流儀で、外部 I/O（DB / Stripe / Valkey）は
 * すべて port 越しに置く。application service は順序と分岐だけを持ち、Stripe SDK には触れない。</p>
 */
interface BillingCustomerPortalAccessGuard {
    /** 不許可なら {@code ENTITLEMENT_005}(403) で fail-closed に拒否する。 */
    void check(long actorId, EntitlementScopeKind scopeKind, long scopeId);
}

interface BillingCustomerPortalCustomerRepository {
    /**
     * scope が所有する <b>ACTIVE な</b> Customer だけを返す。
     *
     * <p>AC-63: ACTIVE 以外（PROVISIONING / PROVISION_FAILED / MIGRATION_REQUIRED …）は
     * そもそも Portal を開始してはならないため、状態別の分岐を呼び出し側へ露出させず
     * 「ACTIVE が引けたか否か」だけを返す（引けなければ 409）。</p>
     */
    Optional<BillingCustomerPortalCustomer> findActiveByScope(EntitlementScopeKind scopeKind, long scopeId);
}

interface BillingCustomerPortalRateLimiter {
    /**
     * scope ごとの Portal 発行回数を 1 回消費する（AC-71: 10 回/時）。
     *
     * @return 上限内なら {@code true}、超過なら {@code false}
     */
    boolean tryConsume(EntitlementScopeKind scopeKind, long scopeId);
}

interface BillingCustomerPortalGateway {
    /**
     * Portal セッションを発行する（AC-64〜AC-68 / AC-73 / AC-74）。
     *
     * <p>実装は「return state の nonce 登録 → Stripe セッション作成 → URL 返却」の順で進み、
     * configuration 未照合なら Stripe を呼ばずに {@code ENTITLEMENT_027}(503) で拒否する。</p>
     */
    BillingCustomerPortalResult createSession(BillingCustomerPortalRequest request);
}

/** Portal を開く対象 Customer。PII は載せない。 */
record BillingCustomerPortalCustomer(UUID id, EntitlementScopeKind scopeKind, long scopeId,
                                     String stripeCustomerRef) { }

/** Portal セッション発行の入力。任意の return URL は<b>受け取らない</b>（AC-67）。 */
record BillingCustomerPortalRequest(long actorId, EntitlementScopeKind scopeKind, long scopeId,
                                    UUID billingCustomerId, String stripeCustomerRef,
                                    String stripeIdempotencyKey) { }

/** Portal セッション発行の結果。URL は監査・ログへ出さない（AC-72）。 */
record BillingCustomerPortalResult(String portalUrl, Instant issuedAt) { }

/**
 * 起動時に照合した Portal configuration の確定像（AC-64 / AC-65 / AC-66）。
 *
 * <p>照合を通った configuration ID だけがセッション作成へ渡る。未照合時は本 record が
 * 存在しないこと自体が fail-closed の根拠になる（「環境変数を素通しで渡す」経路を作らない）。</p>
 */
record BillingCustomerPortalConfigurationSnapshot(String configuration, Instant verifiedAt) { }
