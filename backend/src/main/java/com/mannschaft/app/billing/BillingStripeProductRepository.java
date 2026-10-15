package com.mannschaft.app.billing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * {@link BillingStripeProductEntity} の永続化・DB先読み検索（決定9改訂）。
 *
 * <p>AC-88b: この検索がヒットする限り Stripe の Product 系 API は一切呼ばれない。
 * {@code stripeTaxCode=null} の組も {@code stripe_tax_code_norm} 生成列で一意に扱われる（DB側）。</p>
 */
public interface BillingStripeProductRepository extends JpaRepository<BillingStripeProductEntity, UUID> {

    Optional<BillingStripeProductEntity> findByProductKindAndProductKeyAndStripeTaxCode(
            BillingProductKind productKind, String productKey, String stripeTaxCode);
}
