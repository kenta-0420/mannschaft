package com.mannschaft.app.billing.api;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** V198 {@code billing_checkout_reconciliations} の退避・回収 query。 */
public interface BillingCheckoutReconciliationJpaRepository
        extends JpaRepository<BillingCheckoutReconciliationEntity, UUID> {

    Optional<BillingCheckoutReconciliationEntity> findByStripeSessionRef(String stripeSessionRef);

    /**
     * 既に退避済みの Session が再び倒れたときの冪等な積み増し。行は増やさず
     * {@code attempt_count} を積み、状態を未回収（PENDING）へ戻す。
     *
     * @return 更新行数（1 のときだけ既存行への積み増し成立）
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update BillingCheckoutReconciliationEntity entry
               set entry.attemptCount = entry.attemptCount + 1,
                   entry.lastErrorAt = :now,
                   entry.updatedAt = :now,
                   entry.status = com.mannschaft.app.billing.api.BillingCheckoutReconciliationStatus.PENDING
             where entry.stripeSessionRef = :stripeSessionRef
            """)
    int recordRetry(@Param("stripeSessionRef") String stripeSessionRef, @Param("now") Instant now);

    /** 未回収の退避件数（運用アラートの正本。SQL 一発で数えられること）。 */
    long countByStatus(BillingCheckoutReconciliationStatus status);
}
