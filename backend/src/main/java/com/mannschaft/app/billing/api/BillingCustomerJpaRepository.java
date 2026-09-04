package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.EntitlementScopeKind;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** V196 {@code billing_customers} の scope 所有照合つき取得。 */
public interface BillingCustomerJpaRepository extends JpaRepository<BillingCustomerEntity, UUID> {

    /**
     * scope（種別・ID）と Customer ID の三点一致かつ指定 status の行だけを返す。
     * 他 scope 所有の Customer は id 一致でも引けない（IDOR 防止）。
     */
    Optional<BillingCustomerEntity> findByIdAndScopeKindAndScopeIdAndStatusAndDeletedAtIsNull(
            UUID id, EntitlementScopeKind scopeKind, Long scopeId, String status);

    /** quote 生成時に scope の Customer を解決する（Checkout 時は上の三点一致版で再照合する）。 */
    Optional<BillingCustomerEntity> findByScopeKindAndScopeIdAndStatusAndDeletedAtIsNull(
            EntitlementScopeKind scopeKind, Long scopeId, String status);
}
