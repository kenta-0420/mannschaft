package com.mannschaft.app.billing;

import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** V196 価格 revision / band の Entity マッピングと Repository query を実 MySQL で確認する。 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("BillingPriceVersionRepository 永続化テスト（Testcontainers MySQL）")
class BillingPriceVersionRepositoryIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private BillingPriceVersionRepository priceVersionRepository;

    @Autowired
    private BillingPriceBandVersionRepository priceBandVersionRepository;

    @Test
    @DisplayName("revision と band を BINARY(16) UUIDv7・enum・JSON を保ったまま永続化できる")
    void persistsRevisionAndBand() {
        LocalDateTime effectiveFrom = LocalDateTime.of(2026, 9, 1, 0, 0);
        BillingPriceVersionEntity revision = priceVersionRepository.save(newRevision("catalog-202609", 1L, effectiveFrom));
        BillingPriceBandVersionEntity band = priceBandVersionRepository.save(newBand(revision, 1, 1, null));

        assertThat(revision.getId()).isNotNull();
        assertThat(band.getId()).isNotNull();

        BillingPriceBandVersionEntity found = priceBandVersionRepository
                .findByIdAndDeletedAtIsNull(band.getId())
                .orElseThrow();
        assertThat(found.getPriceVersionId()).isEqualTo(revision.getId());
        assertThat(found.getTaxBehavior()).isEqualTo(BillingTaxBehavior.INCLUSIVE);
        assertThat(found.getTaxMasterSnapshot()).isEqualTo("{\"rate\":10}");
        assertThat(found.getCurrency()).isEqualTo("JPY");
        assertThat(found.isIncludedInPrice()).isTrue();
        assertThat(found.getLockVersion()).isZero();
    }

    @Test
    @DisplayName("時点・人数・状態で販売候補を絞り、revision と全 band を悲観ロック取得できる")
    void findsEffectiveCandidatesAndLocksRevisionBands() {
        LocalDateTime effectiveFrom = LocalDateTime.of(2026, 9, 1, 0, 0);
        BillingPriceVersionEntity revision = priceVersionRepository.save(newRevision("catalog-202609", 2L, effectiveFrom));
        priceBandVersionRepository.save(newBand(revision, 1, 1, 10));
        priceBandVersionRepository.save(newBand(revision, 2, 11, null));

        List<BillingPriceVersionEntity> revisions = priceVersionRepository.findEffectiveCandidates(
                BillingProductKind.PLAN,
                "standard",
                EntitlementScopeKind.TEAM,
                List.of(BillingPriceVersionStatus.ACTIVE),
                effectiveFrom.plusSeconds(1));
        List<BillingPriceBandVersionEntity> bands = priceBandVersionRepository.findEffectiveCandidates(
                BillingProductKind.PLAN,
                "standard",
                EntitlementScopeKind.TEAM,
                List.of(BillingPriceVersionStatus.ACTIVE),
                effectiveFrom.plusSeconds(1),
                11);

        assertThat(revisions).extracting(BillingPriceVersionEntity::getId).containsExactly(revision.getId());
        assertThat(bands).extracting(BillingPriceBandVersionEntity::getBandNo).containsExactly(2);
        assertThat(priceVersionRepository.findAllForUpdate(
                BillingProductKind.PLAN, "standard", EntitlementScopeKind.TEAM))
                .extracting(BillingPriceVersionEntity::getId).contains(revision.getId());
        assertThat(priceBandVersionRepository.findAllByPriceVersionIdForUpdate(revision.getId()))
                .extracting(BillingPriceBandVersionEntity::getBandNo).containsExactly(1, 2);
    }

    private BillingPriceVersionEntity newRevision(String catalogRevision, Long revisionNo, LocalDateTime effectiveFrom) {
        return BillingPriceVersionEntity.builder()
                .productKind(BillingProductKind.PLAN)
                .productKey("standard")
                .scopeKind(EntitlementScopeKind.TEAM)
                .catalogRevision(catalogRevision)
                .revisionNo(revisionNo)
                .status(BillingPriceVersionStatus.ACTIVE)
                .effectiveFrom(effectiveFrom)
                .creationSource(BillingPriceCreationSource.SYSTEM_BACKFILL)
                .build();
    }

    private BillingPriceBandVersionEntity newBand(
            BillingPriceVersionEntity revision, int bandNo, int minMembers, Integer maxMembers) {
        return BillingPriceBandVersionEntity.builder()
                .productKind(revision.getProductKind())
                .productKey(revision.getProductKey())
                .scopeKind(revision.getScopeKind())
                .bandNo(bandNo)
                .minMembers(minMembers)
                .maxMembers(maxMembers)
                .priceVersionId(revision.getId())
                .stripePriceRef("price_test_" + revision.getRevisionNo() + "_" + bandNo)
                .inputAmount(1000L)
                .taxBehavior(BillingTaxBehavior.INCLUSIVE)
                .taxCodeSnapshot("txcd_10000000")
                .taxMasterSnapshot("{\"rate\":10}")
                .amountExcludingTax(909L)
                .taxAmount(91L)
                .taxRateBasisPoints(1000)
                .taxNameSnapshot("消費税")
                .includedInPrice(true)
                .amountIncludingTax(1000L)
                .effectiveFrom(revision.getEffectiveFrom())
                .status(BillingPriceVersionStatus.ACTIVE)
                .creationSource(BillingPriceCreationSource.SYSTEM_BACKFILL)
                .build();
    }
}
