package com.mannschaft.app.billing.api;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** V196 {@code billing_invoices} の JPA repository。 */
public interface BillingInvoiceJpaRepository extends JpaRepository<BillingInvoiceEntity, UUID> {

    Optional<BillingInvoiceEntity> findByPspInvoiceRef(String pspInvoiceRef);
}
