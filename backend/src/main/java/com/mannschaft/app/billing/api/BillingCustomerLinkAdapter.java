package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.BillingCustomerLinkPort;
import com.mannschaft.app.billing.EntitlementScopeKind;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * {@link BillingCustomerLinkPort} の実装（{@code billing_customers} を持つ {@code billing.api} 側に置く）。
 *
 * <p>ポートの Javadoc にある実在欠陥（F20.1 決済フロー由来の契約は {@code billing_customer_id} を
 * 持たない）を、Saga の予約時に一度だけ引き上げることで解消する。</p>
 *
 * <p><b>status と psp_customer_ref の対</b>: V196 の {@code chk_bcu_ref_by_status} は
 * 「{@code ACTIVE} なら ref 非 NULL」「{@code PROVISIONING} なら ref は NULL」を強制する。
 * 契約が ref を持っていれば Stripe Customer は実在するので {@code ACTIVE} で作り、
 * 持っていなければ {@code PROVISIONING} で作る。どちらの分岐も CHECK を満たす。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
class BillingCustomerLinkAdapter implements BillingCustomerLinkPort {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_PROVISIONING = "PROVISIONING";

    private final BillingCustomerJpaRepository billingCustomerJpaRepository;
    private final Clock clock;

    /**
     * {@inheritDoc}
     *
     * <p>{@code MANDATORY}: 呼び出し元（Saga の tx1）で契約行が {@code FOR UPDATE} 済みであることを
     * 前提にする。独立 tx にすると、引き上げだけが commit されて予約が巻き戻る組み合わせが生まれる。</p>
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID resolveOrProvision(
            EntitlementScopeKind scopeKind, Long scopeId, Long organizationId, String pspCustomerRef) {

        // uk_bcu_scope は deleted_at を含まない（＝論理削除済みでも UNIQUE を占有する）ため、
        // 存在判定も deleted_at で絞らない。絞ると「見つからないのに INSERT できない」状態になる。
        UUID existing = billingCustomerJpaRepository.findByScopeKindAndScopeId(scopeKind, scopeId)
                .map(BillingCustomerEntity::getId)
                .orElse(null);
        if (existing != null) {
            return existing;
        }
        try {
            return provision(scopeKind, scopeId, organizationId, pspCustomerRef);
        } catch (DataIntegrityViolationException e) {
            // 並行する同一 scope の予約が先に INSERT した。握り潰しではなく
            // 「先客が居る」という UNIQUE の事実なので、読み直して同じ答えを返す。
            return billingCustomerJpaRepository.findByScopeKindAndScopeId(scopeKind, scopeId)
                    .map(BillingCustomerEntity::getId)
                    .orElseThrow(() -> e);
        }
    }

    private UUID provision(
            EntitlementScopeKind scopeKind, Long scopeId, Long organizationId, String pspCustomerRef) {
        Instant now = clock.instant();
        BillingCustomerEntity created = BillingCustomerEntity.builder()
                .scopeKind(scopeKind)
                .scopeId(scopeId)
                .organizationId(organizationId)
                .pspCustomerRef(pspCustomerRef)
                .status(pspCustomerRef == null ? STATUS_PROVISIONING : STATUS_ACTIVE)
                .provisionAttempts(0)
                .version(0L)
                .createdAt(now)
                .updatedAt(now)
                .build();
        billingCustomerJpaRepository.saveAndFlush(created);
        log.info("PR6a: billing_customers を引き上げた（F20.1 決済フロー由来の契約は billing_customer_id を"
                + "持たないため）: scopeKind={}, scopeId={}", scopeKind, scopeId);
        return created.getId();
    }
}
