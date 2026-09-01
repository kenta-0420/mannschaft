package com.mannschaft.app.billing;

import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/** 実MySQLの行ロックで、公開pricingの同時遅延昇格が一回だけになることを検証する。 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("価格版lazy promotion 実MySQL競合")
class BillingPricePromotionConcurrencyIT extends AbstractMySqlIntegrationTest {

    private static final long TIMEOUT_SECONDS = 10L;
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @Autowired private BillingPricePromotionService promotionService;
    @Autowired private BillingPriceVersionRepository versionRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @PersistenceContext private EntityManager entityManager;

    private String productKey;
    private UUID currentId;
    private UUID nextId;

    @BeforeEach
    void setUp() {
        productKey = "IT-LAZY-" + System.nanoTime();
        transactionTemplate.executeWithoutResult(tx -> {
            BillingPriceVersionEntity current = persistVersion(
                    "current", 1L, BillingPriceVersionStatus.ACTIVE,
                    Instant.parse("2026-08-01T00:00:00Z"), NOW);
            BillingPriceVersionEntity next = persistVersion(
                    "next", 2L, BillingPriceVersionStatus.SCHEDULED, NOW, null);
            currentId = current.getId();
            nextId = next.getId();
            persistBand(current, BillingPriceVersionStatus.ACTIVE, "price_it_current");
            persistBand(next, BillingPriceVersionStatus.SCHEDULED, "price_it_next");
        });
    }

    @AfterEach
    void tearDown() {
        if (productKey == null) {
            return;
        }
        transactionTemplate.executeWithoutResult(tx -> {
            entityManager.createNativeQuery("DELETE FROM billing_price_band_versions WHERE product_key = :key")
                    .setParameter("key", productKey).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM billing_price_versions WHERE product_key = :key")
                    .setParameter("key", productKey).executeUpdate();
        });
    }

    @Test
    @DisplayName("先行row lock中は昇格が待機し、解放後の二重要求でも遷移は一回だけ")
    void rowLockSerializesConcurrentLazyPromotion() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> locker = executor.submit(() -> transactionTemplate.executeWithoutResult(tx -> {
                versionRepository.findAllForUpdate(
                        BillingProductKind.PLAN, productKey, EntitlementScopeKind.USER);
                locked.countDown();
                await(release);
            }));
            assertThat(locked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            Future<Boolean> firstPromotion = executor.submit(() -> promotionService.promoteDue(
                    BillingProductKind.PLAN, productKey, EntitlementScopeKind.USER, NOW));
            assertThat(completesWithin(firstPromotion, 300)).isFalse();

            release.countDown();
            locker.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(firstPromotion.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            assertThat(promotionService.promoteDue(
                    BillingProductKind.PLAN, productKey, EntitlementScopeKind.USER, NOW)).isFalse();

            transactionTemplate.executeWithoutResult(tx -> {
                assertThat(versionRepository.findById(currentId).orElseThrow().getStatus())
                        .isEqualTo(BillingPriceVersionStatus.RETIRED);
                assertThat(versionRepository.findById(nextId).orElseThrow().getStatus())
                        .isEqualTo(BillingPriceVersionStatus.ACTIVE);
            });
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }

    private BillingPriceVersionEntity persistVersion(
            String revision, long revisionNo, BillingPriceVersionStatus status,
            Instant from, Instant until) {
        BillingPriceVersionEntity entity = BillingPriceVersionEntity.builder()
                .productKind(BillingProductKind.PLAN).productKey(productKey)
                .scopeKind(EntitlementScopeKind.USER).catalogRevision(revision)
                .revisionNo(revisionNo).status(status).provisionAttempts(0)
                .effectiveFrom(from).effectiveUntil(until)
                .creationSource(BillingPriceCreationSource.SYSTEM_BACKFILL).build();
        entityManager.persist(entity);
        entityManager.flush();
        return entity;
    }

    private void persistBand(
            BillingPriceVersionEntity version, BillingPriceVersionStatus status, String stripeRef) {
        BillingPriceBandVersionEntity band = BillingPriceBandVersionEntity.builder()
                .productKind(BillingProductKind.PLAN).productKey(productKey)
                .scopeKind(EntitlementScopeKind.USER).bandNo(1).minMembers(1)
                .priceVersionId(version.getId()).stripePriceRef(stripeRef + "_" + System.nanoTime())
                .currency("JPY").inputAmount(1_100L).taxBehavior(BillingTaxBehavior.INCLUSIVE)
                .taxCodeSnapshot("txcd_10000000").taxMasterSnapshot("{}")
                .amountExcludingTax(1_000L).taxAmount(100L).taxRateBasisPoints(1_000)
                .taxNameSnapshot("消費税").includedInPrice(true).amountIncludingTax(1_100L)
                .effectiveFrom(version.getEffectiveFrom()).effectiveUntil(version.getEffectiveUntil())
                .status(status).creationSource(BillingPriceCreationSource.SYSTEM_BACKFILL).build();
        entityManager.persist(band);
    }

    private static boolean completesWithin(Future<?> future, long millis) throws Exception {
        try {
            future.get(millis, TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException expected) {
            return false;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("row lock解放待ちがtimeoutしました");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("row lock試験がinterruptされました", interrupted);
        }
    }
}
