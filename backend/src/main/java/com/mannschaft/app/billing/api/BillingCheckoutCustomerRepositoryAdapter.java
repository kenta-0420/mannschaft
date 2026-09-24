package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.EntitlementScopeKind;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/** {@link BillingCheckoutCustomerRepository} の JPA 実装。 */
@Component
@RequiredArgsConstructor
class BillingCheckoutCustomerRepositoryAdapter implements BillingCheckoutCustomerRepository {

    /** Checkout に使える唯一の Customer 状態。 */
    private static final String ACTIVE = "ACTIVE";

    private final BillingCustomerJpaRepository customerJpaRepository;

    @Override
    public Optional<BillingCheckoutCustomer> findScopeOwnedActive(
            EntitlementScopeKind scopeKind, long scopeId, UUID billingCustomerId) {
        if (scopeKind == null || billingCustomerId == null) {
            return Optional.empty();
        }
        return customerJpaRepository
                .findByIdAndScopeKindAndScopeIdAndStatusAndDeletedAtIsNull(
                        billingCustomerId, scopeKind, scopeId, ACTIVE)
                .map(entity -> new BillingCheckoutCustomer(entity.getId(), entity.getScopeKind(),
                        entity.getScopeId(), entity.getPspCustomerRef(), entity.getStatus()));
    }
}
