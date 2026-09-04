package com.mannschaft.app.billing.api;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.billing.EntitlementErrorCode;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.api.dto.BillingCustomerPortalSessionResponse;
import com.mannschaft.app.billing.api.dto.CreateBillingCustomerPortalSessionRequest;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * PR5 Billing Center: Stripe Customer Portal セッション発行（AC-61〜AC-74）。
 *
 * <p>順序は <b>scope 認可 → Customer が ACTIVE か → scope ごとの回数制限 → Portal 発行</b>。
 * Stripe へ出る前に打ち切れる判定を先に置き、外部 I/O を無駄に発生させない。</p>
 *
 * <ul>
 *   <li>AC-61 / AC-62: 他 scope は {@code ENTITLEMENT_005}(403)、未認証は Security 層で 401。</li>
 *   <li>AC-63: Customer が ACTIVE 以外なら Portal を開始せず {@code ENTITLEMENT_024}(409)。</li>
 *   <li>AC-71: scope ごと 10 回/時。超過は {@code ENTITLEMENT_028}(429)。</li>
 *   <li>AC-72: 監査 {@code BILLING_PORTAL_OPENED} を記録する。URL は含めない。</li>
 * </ul>
 *
 * <p><b>{@code @Transactional} を持たない理由</b>: 本サービスは読み取り 1 件と外部 I/O だけで構成され、
 * 書き込みは gateway 内の nonce 登録（自前の短いトランザクション）のみである。外部 I/O を
 * トランザクションで囲うと Stripe の応答待ちの間 DB コネクションを保持することになる。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingCustomerPortalApplicationService {

    /** 耐久冪等性（AC-69 / AC-70）が本 API を識別するための HTTP method / path の正本。 */
    static final String IDEMPOTENCY_METHOD = "POST";
    static final String IDEMPOTENCY_PATH = "/api/v1/me/billing/portal-sessions";

    /** Stripe 側の二重 Session 作成を塞ぐキーの接頭辞。 */
    private static final String STRIPE_IDEMPOTENCY_PREFIX = "billing-portal:";

    private final BillingCustomerPortalAccessGuard scopeGuard;
    private final BillingCustomerPortalCustomerRepository customerRepository;
    private final BillingCustomerPortalRateLimiter rateLimiter;
    private final BillingCustomerPortalGateway portalGateway;
    private final AuditLogService auditLogService;

    /**
     * Portal セッションを発行する。
     *
     * @param actorId        操作者
     * @param request        対象 scope（任意の return URL は受け取らない）
     * @param idempotencyKey 冪等キー（Stripe 側の再送束縛にも使う）
     * @return Portal の短命 URL と発行時刻
     */
    public BillingCustomerPortalSessionResponse create(
            long actorId, CreateBillingCustomerPortalSessionRequest request, String idempotencyKey) {
        EntitlementScopeKind scopeKind = request == null ? null : request.scopeKind();
        Long scopeId = request == null ? null : request.scopeId();
        if (scopeKind == null || scopeId == null) {
            throw new BusinessException(EntitlementErrorCode.INVALID_SCOPE_KIND);
        }

        // AC-61: 判定正本は BillingAccessGuard（他 scope は 403）。
        scopeGuard.check(actorId, scopeKind, scopeId);

        // AC-63: ACTIVE 以外の Customer では Portal を開始しない。状態は外へ露出させず 409 に畳む。
        BillingCustomerPortalCustomer customer = customerRepository
                .findActiveByScope(scopeKind, scopeId)
                .filter(found -> found.stripeCustomerRef() != null && !found.stripeCustomerRef().isBlank())
                .orElseThrow(() -> new BusinessException(EntitlementErrorCode.MIGRATION_REQUIRED));

        // AC-71: scope ごと 10 回/時。10 回目までは通し、11 回目を 429 で拒否する。
        if (!rateLimiter.tryConsume(scopeKind, scopeId)) {
            throw new BusinessException(EntitlementErrorCode.PORTAL_RATE_LIMITED);
        }

        BillingCustomerPortalResult result = portalGateway.createSession(
                new BillingCustomerPortalRequest(actorId, scopeKind, scopeId, customer.id(),
                        customer.stripeCustomerRef(), stripeIdempotencyKey(idempotencyKey, customer)));

        recordPortalOpened(actorId, scopeKind, scopeId, customer);
        return new BillingCustomerPortalSessionResponse(result.portalUrl(), result.issuedAt());
    }

    /**
     * AC-72: 監査に残すのは actor / scope / 対象 Customer の参照までとし、
     * <b>Portal の URL は記録しない</b>（正本 §370: カード番号・住所全文・URL・payload は除外）。
     */
    private void recordPortalOpened(long actorId, EntitlementScopeKind scopeKind, long scopeId,
                                    BillingCustomerPortalCustomer customer) {
        String metadata = String.format(
                "{\"scopeKind\":\"%s\",\"scopeId\":%d,\"billingCustomerId\":\"%s\"}",
                scopeKind.name(), scopeId, customer.id());
        auditLogService.record(AuditEventType.BILLING_PORTAL_OPENED.name(), actorId, null,
                scopeKind == EntitlementScopeKind.TEAM ? Long.valueOf(scopeId) : null,
                scopeKind == EntitlementScopeKind.ORG ? Long.valueOf(scopeId) : null,
                null, null, null, metadata);
    }

    /** 再送時に Stripe 側で同一 Session が返るよう、呼び出し元の冪等キーへ束縛する。 */
    private String stripeIdempotencyKey(String key, BillingCustomerPortalCustomer customer) {
        return Optional.ofNullable(key)
                .filter(value -> !value.isBlank())
                .map(value -> STRIPE_IDEMPOTENCY_PREFIX + value)
                .orElse(STRIPE_IDEMPOTENCY_PREFIX + customer.id());
    }
}
