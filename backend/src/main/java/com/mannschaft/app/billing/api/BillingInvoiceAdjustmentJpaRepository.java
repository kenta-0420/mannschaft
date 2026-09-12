package com.mannschaft.app.billing.api;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** V196 {@code billing_invoice_adjustments} の JPA repository。 */
public interface BillingInvoiceAdjustmentJpaRepository
        extends JpaRepository<BillingInvoiceAdjustmentEntity, UUID> {

    Optional<BillingInvoiceAdjustmentEntity> findByPspObjectRef(String pspObjectRef);

    List<BillingInvoiceAdjustmentEntity> findByInvoiceId(UUID invoiceId);
}
