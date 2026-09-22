package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.BillingPriceBandVersionRepository;
import com.mannschaft.app.billing.BillingPriceVersionRepository;
import com.mannschaft.app.billing.BillingProductKind;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.FeatureCatalogRepository;
import com.mannschaft.app.billing.PlanEntity;
import com.mannschaft.app.billing.PlanRepository;
import com.mannschaft.app.billing.api.dto.PriceBandInput;
import com.mannschaft.app.billing.api.dto.PriceRevisionCreateRequest;
import com.mannschaft.app.billing.tax.BillingTaxCodeEntity;
import com.mannschaft.app.billing.tax.BillingTaxCodeRepository;
import com.mannschaft.app.billing.tax.BillingTaxCodeService;
import com.mannschaft.app.billing.tax.BillingTaxDerivationService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.billing.BillingTaxBehavior;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 価格改定戦役 第8隊: revision create の row lock による直列化を実 DB で検証する
 * （試練・AC-52・AC-53）。
 *
 * <p>同一 {@code (productKind, productKey, scopeKind)} に対する2本の future create を同時に投げても、
 * 一方だけが成功し他方は 409 相当（{@code FUTURE_REVISION_ALREADY_EXISTS} または
 * {@code uk_bpv_revision_no}）で失敗することを確認する。{@code PriceRevisionCreateService} は
 * 本試練時点で未実装であり、コンパイルエラーとして red になることを是とする。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("価格 revision create 直列化 IT（AC-52・AC-53）")
class PriceRevisionOverlapConcurrencyIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private BillingPriceVersionRepository priceVersionRepository;
    @Autowired
    private BillingPriceBandVersionRepository bandVersionRepository;
    @Autowired
    private PlanRepository planRepository;
    @Autowired
    private FeatureCatalogRepository featureCatalogRepository;
    @Autowired
    private BillingTaxCodeRepository taxCodeRepository;

    private PriceRevisionCreateService service;

    @BeforeEach
    void setUp() {
        if (planRepository.findById("FULL_IT").isEmpty()) {
            planRepository.save(PlanEntity.builder().planKey("FULL_IT").enabled(true)
                    .displayNameKey("k").descriptionKey("d").sortOrder(1).build());
        }
        if (taxCodeRepository.findByCodeAndValidFromAndDeletedAtIsNull("JP_STANDARD_10", Instant.EPOCH).isEmpty()) {
            taxCodeRepository.save(BillingTaxCodeEntity.builder()
                    .code("JP_STANDARD_10").displayName("標準税率10%").rateBasisPoints(1000)
                    .validFrom(Instant.EPOCH).enabled(true).build());
        }
        BillingTaxCodeService taxCodeService = new BillingTaxCodeService(taxCodeRepository);
        service = new PriceRevisionCreateService(priceVersionRepository, bandVersionRepository, planRepository,
                featureCatalogRepository, taxCodeService, new BillingTaxDerivationService(), Clock.systemUTC());
    }

    private PriceRevisionCreateRequest requestAt(Instant effectiveFrom) {
        return new PriceRevisionCreateRequest(BillingProductKind.PLAN, "FULL_IT", EntitlementScopeKind.TEAM,
                effectiveFrom, null,
                List.of(new PriceBandInput(1, 1, null, 1000L, BillingTaxBehavior.EXCLUSIVE, "JP_STANDARD_10")));
    }

    @Test
    @DisplayName("AC-52/AC-53: 同一商品への2本の future create を同時実行すると一方のみ成功する")
    void concurrentFutureCreates_onlyOneSucceeds() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();

        Runnable task = () -> {
            try {
                go.await(5, TimeUnit.SECONDS);
                service.create(requestAt(Instant.now().plusSeconds(3600)), 1L);
                success.incrementAndGet();
            } catch (Exception e) {
                failure.incrementAndGet();
            }
        };
        Future<?> f1 = pool.submit(task);
        Future<?> f2 = pool.submit(task);
        go.countDown();
        f1.get(15, TimeUnit.SECONDS);
        f2.get(15, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(success.get()).isEqualTo(1);
        assertThat(failure.get()).isEqualTo(1);
    }
}
