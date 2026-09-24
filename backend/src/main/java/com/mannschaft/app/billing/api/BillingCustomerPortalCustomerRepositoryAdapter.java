package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.EntitlementScopeKind;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * PR5 Portal の Customer 参照 port 実装（AC-63）。
 *
 * <p>{@code billing_customers} から <b>ACTIVE な行だけ</b>を引く。ACTIVE 以外
 * （{@code PROVISIONING} / {@code PROVISION_FAILED} / {@code MIGRATION_REQUIRED} …）は
 * そもそも Portal を開いてよい状態ではないため、状態別の分岐を呼び出し側へ渡さず
 * 「引けなかった」に畳む（呼び出し側は 409 に写す）。</p>
 */
@Component
@RequiredArgsConstructor
class BillingCustomerPortalCustomerRepositoryAdapter implements BillingCustomerPortalCustomerRepository {

    /** Portal を開ける唯一の Customer 状態。 */
    private static final String CUSTOMER_ACTIVE = "ACTIVE";

    private final BillingCustomerJpaRepository customerJpaRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<BillingCustomerPortalCustomer> findActiveByScope(
            EntitlementScopeKind scopeKind, long scopeId) {
        return customerJpaRepository
                .findByScopeKindAndScopeIdAndStatusAndDeletedAtIsNull(scopeKind, scopeId, CUSTOMER_ACTIVE)
                .map(entity -> new BillingCustomerPortalCustomer(
                        entity.getId(), entity.getScopeKind(), entity.getScopeId(),
                        entity.getPspCustomerRef()));
    }
}
