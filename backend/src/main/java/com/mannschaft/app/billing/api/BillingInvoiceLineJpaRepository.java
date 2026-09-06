package com.mannschaft.app.billing.api;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** V196 {@code billing_invoice_lines} の JPA repository。 */
public interface BillingInvoiceLineJpaRepository extends JpaRepository<BillingInvoiceLineEntity, UUID> {

    List<BillingInvoiceLineEntity> findByInvoiceId(UUID invoiceId);

    Optional<BillingInvoiceLineEntity> findByInvoiceIdAndPspLineRef(UUID invoiceId, String pspLineRef);
}
