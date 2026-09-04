package com.mannschaft.app.billing.api;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** V196 {@code billing_invoices} の JPA repository。 */
public interface BillingInvoiceJpaRepository extends JpaRepository<BillingInvoiceEntity, UUID> {

    Optional<BillingInvoiceEntity> findByPspInvoiceRef(String pspInvoiceRef);

    /**
     * F20.1 PR5: dispute（charge しか持たない）から対象 invoice を辿るための逆引き。
     *
     * <p>charge → billing_customer までは {@code stripe_webhook_events.stripe_object_ref} の記録で辿れる。
     * その顧客の直近の請求書を対象とする。将来 {@code psp_charge_ref} 相当の列を足して一意化すべき。</p>
     */
    Optional<BillingInvoiceEntity>
            findFirstByBillingCustomerIdAndDeletedAtIsNullOrderByPeriodEndDescCreatedAtDesc(UUID billingCustomerId);
}
