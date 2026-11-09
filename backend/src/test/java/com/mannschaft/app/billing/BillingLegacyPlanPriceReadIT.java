package com.mannschaft.app.billing;

import com.mannschaft.app.billing.api.BillingCatalogQueryService;
import com.mannschaft.app.billing.api.dto.PlanItem;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-260930-1931 AC01: 旧価格と異なる新ACTIVE価格をlegacy入口から読む先行試練。
 * 実Beanと実MySQLを使い、fixtureをcommitしてから別の読取トランザクションへ渡す。
 * HTTP認可・契約保存の証拠は別試練が担い、この試験で代用しない。
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("1931 legacy PLAN価格の正本読取")
class BillingLegacyPlanPriceReadIT extends AbstractMySqlIntegrationTest {

    private static final int LEGACY_PRICE = 1_100;
    private static final long ACTIVE_PRICE = 3_630L;
    private static final Instant EFFECTIVE_FROM = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired private PlanRepository planRepository;
    @Autowired private PlanPriceBandRepository legacyBandRepository;
    @Autowired private BillingPriceVersionRepository versionRepository;
    @Autowired private BillingPriceBandVersionRepository bandRepository;
    @Autowired private BillingCatalogQueryService catalogService;
    @Autowired private BillingPriceResolver priceResolver;
    @Autowired private TransactionTemplate transactions;

    private String planKey;
    private final List<UUID> versionIds = new ArrayList<>();
    private final List<UUID> bandIds = new ArrayList<>();

    @BeforeEach
    void 検証専用の旧価格と異なるACTIVE価格を保存する() {
        planKey = "I1931-" + UUID.randomUUID().toString().substring(0, 16);
        transactions.executeWithoutResult(tx -> {
            planRepository.saveAndFlush(PlanEntity.builder().planKey(planKey)
                    .displayNameKey("billing.it1931.name").descriptionKey("billing.it1931.description")
                    .baseMonthlyPriceJpy(LEGACY_PRICE).sortOrder(0).enabled(true).build());
            for (EntitlementScopeKind scope : EntitlementScopeKind.values()) {
                if (scope != EntitlementScopeKind.USER) {
                    legacyBandRepository.saveAndFlush(PlanPriceBandEntity.builder()
                            .planKey(planKey).scopeKind(scope == EntitlementScopeKind.TEAM
                                    ? PlanPriceBandScopeKind.TEAM : PlanPriceBandScopeKind.ORG)
                            .bandNo((short) 1).minMembers(1).monthlyPriceJpy(LEGACY_PRICE).build());
                }
                BillingPriceVersionEntity version = versionRepository.saveAndFlush(
                        BillingPriceVersionEntity.builder().productKind(BillingProductKind.PLAN)
                                .productKey(planKey).scopeKind(scope).catalogRevision("1931-active")
                                .revisionNo(1L).status(BillingPriceVersionStatus.ACTIVE)
                                .effectiveFrom(EFFECTIVE_FROM).provisionAttempts(0)
                                .creationSource(BillingPriceCreationSource.SYSTEM_BACKFILL).build());
                versionIds.add(version.getId());
                BillingPriceBandVersionEntity band = bandRepository.saveAndFlush(
                        BillingPriceBandVersionEntity.builder().productKind(BillingProductKind.PLAN)
                                .productKey(planKey).scopeKind(scope).priceVersionId(version.getId())
                                .bandNo(1).minMembers(1).currency("JPY")
                                .stripePriceRef("price_it1931_" + UUID.randomUUID())
                                .inputAmount(ACTIVE_PRICE).taxBehavior(BillingTaxBehavior.INCLUSIVE)
                                .taxCodeSnapshot("txcd_10000000").taxMasterSnapshot("{}")
                                .amountExcludingTax(3_300L).taxAmount(330L)
                                .amountIncludingTax(ACTIVE_PRICE).taxRateBasisPoints(1_000)
                                .taxNameSnapshot("消費税").includedInPrice(true)
                                .effectiveFrom(EFFECTIVE_FROM).status(BillingPriceVersionStatus.ACTIVE)
                                .creationSource(BillingPriceCreationSource.SYSTEM_BACKFILL).build());
                bandIds.add(band.getId());
            }
        });
    }

    @AfterEach
    void 所有fixtureだけを削除する() {
        transactions.executeWithoutResult(tx -> {
            bandRepository.deleteAllById(bandIds);
            bandRepository.flush();
            versionRepository.deleteAllById(versionIds);
            versionRepository.flush();
            if (planKey != null) {
                legacyBandRepository.deleteAll(legacyBandRepository
                        .findByPlanKeyAndScopeKindOrderByBandNoAsc(planKey, PlanPriceBandScopeKind.TEAM));
                legacyBandRepository.deleteAll(legacyBandRepository
                        .findByPlanKeyAndScopeKindOrderByBandNoAsc(planKey, PlanPriceBandScopeKind.ORG));
                legacyBandRepository.flush();
                planRepository.deleteById(planKey);
            }
        });
    }

    @Test
    void 会員カタログ_USER月額は旧baseでなく新ACTIVE税込額である() {
        PlanItem result = catalogService.getCatalog().getPlans().stream()
                .filter(plan -> planKey.equals(plan.getPlanKey())).findFirst().orElseThrow();

        assertThat(result.getBaseMonthlyPriceJpy()).isEqualTo((int) ACTIVE_PRICE);
    }

    @Test
    void 会員カタログ_TEAMとORG帯は旧表でなく新ACTIVE税込額である() {
        PlanItem result = catalogService.getCatalog().getPlans().stream()
                .filter(plan -> planKey.equals(plan.getPlanKey())).findFirst().orElseThrow();

        assertThat(result.getPriceBands()).hasSize(2).allSatisfy(band ->
                assertThat(band.getMonthlyPriceJpy()).isEqualTo((int) ACTIVE_PRICE));
    }

    @Test
    void 契約価格解決_USER月額は旧baseでなく新ACTIVE税込額である() {
        Integer result = priceResolver.resolveMonthlyPriceJpy(
                EntitlementScopeKind.USER, 1L, ContractKind.PLAN, planKey, null);

        assertThat(result).isEqualTo((int) ACTIVE_PRICE);
    }
}
