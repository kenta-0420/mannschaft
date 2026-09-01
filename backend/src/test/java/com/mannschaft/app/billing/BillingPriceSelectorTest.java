package com.mannschaft.app.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("販売価格now selector")
class BillingPriceSelectorTest {

    @Mock private BillingPricePromotionService promotionService;
    @Mock private BillingPriceSnapshotReader snapshotReader;

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    @DisplayName("昇格処理後に再読込した唯一のACTIVE revisionと全bandを返す")
    void returnsUniqueActiveRevisionAfterPromotion() {
        BillingPriceVersionEntity version = version();
        BillingPriceBandVersionEntity band = band(version, 1_100L, "price_full_user_1");
        LocalDateTime now = LocalDateTime.now(clock);
        given(snapshotReader.readActive(BillingProductKind.PLAN, "FULL", EntitlementScopeKind.USER, now))
                .willReturn(java.util.Optional.of(new BillingPriceSnapshotReader.Candidate(version, List.of(band))));
        BillingPriceSelector selector = new BillingPriceSelector(promotionService, snapshotReader, clock);

        assertThat(selector.selectNow(BillingProductKind.PLAN, "FULL", EntitlementScopeKind.USER))
                .get().extracting(selected -> selected.bands().getFirst().getAmountIncludingTax())
                .isEqualTo(1_100L);
    }

    @Test
    @DisplayName("0円又はStripe Price未設定のbandは販売不可に倒す")
    void rejectsNonSellableBand() {
        BillingPriceVersionEntity version = version();
        BillingPriceBandVersionEntity band = band(version, 0L, null);
        LocalDateTime now = LocalDateTime.now(clock);
        given(snapshotReader.readActive(BillingProductKind.PLAN, "FULL", EntitlementScopeKind.USER, now))
                .willReturn(java.util.Optional.of(new BillingPriceSnapshotReader.Candidate(version, List.of(band))));
        BillingPriceSelector selector = new BillingPriceSelector(promotionService, snapshotReader, clock);

        assertThat(selector.selectNow(BillingProductKind.PLAN, "FULL", EntitlementScopeKind.USER)).isEmpty();
    }

    @Test
    @DisplayName("1-20、21-50、51以上の連続band境界を販売可能として保持する")
    void acceptsTwentyTwentyOneAndFiftyFiftyOneBoundaries() {
        BillingPriceVersionEntity version = version();
        List<BillingPriceBandVersionEntity> bands = List.of(
                band(version, 1, 1, 20, 1_100L),
                band(version, 2, 21, 50, 2_200L),
                band(version, 3, 51, null, 3_300L));
        LocalDateTime now = LocalDateTime.now(clock);
        given(snapshotReader.readActive(BillingProductKind.PLAN, "FULL", EntitlementScopeKind.USER, now))
                .willReturn(java.util.Optional.of(new BillingPriceSnapshotReader.Candidate(version, bands)));
        BillingPriceSelector selector = new BillingPriceSelector(promotionService, snapshotReader, clock);

        assertThat(selector.selectNow(BillingProductKind.PLAN, "FULL", EntitlementScopeKind.USER))
                .get().extracting(selected -> selected.bands().size()).isEqualTo(3);
    }

    @Test
    @DisplayName("人数範囲が重複するbandは販売不可に倒す")
    void rejectsOverlappingMemberBands() {
        BillingPriceVersionEntity version = version();
        List<BillingPriceBandVersionEntity> bands = List.of(
                band(version, 1, 1, 20, 1_100L),
                band(version, 2, 20, null, 2_200L));
        LocalDateTime now = LocalDateTime.now(clock);
        given(snapshotReader.readActive(BillingProductKind.PLAN, "FULL", EntitlementScopeKind.USER, now))
                .willReturn(java.util.Optional.of(new BillingPriceSnapshotReader.Candidate(version, bands)));
        BillingPriceSelector selector = new BillingPriceSelector(promotionService, snapshotReader, clock);

        assertThat(selector.selectNow(BillingProductKind.PLAN, "FULL", EntitlementScopeKind.USER)).isEmpty();
    }

    @Test
    @DisplayName("人数帯の下限又は税方式が欠けていても例外にせず販売不可にする")
    void rejectsMissingRangeOrTaxBehavior() {
        BillingPriceVersionEntity version = version();
        BillingPriceBandVersionEntity missingMinimum = band(version, 1, 1, null, 1_100L);
        missingMinimum.setMinMembers(null);
        LocalDateTime now = LocalDateTime.now(clock);
        given(snapshotReader.readActive(BillingProductKind.PLAN, "FULL", EntitlementScopeKind.USER, now))
                .willReturn(java.util.Optional.of(new BillingPriceSnapshotReader.Candidate(
                        version, List.of(missingMinimum))));
        BillingPriceSelector selector = new BillingPriceSelector(promotionService, snapshotReader, clock);

        assertThat(selector.selectNow(BillingProductKind.PLAN, "FULL", EntitlementScopeKind.USER)).isEmpty();

        BillingPriceBandVersionEntity missingTaxBehavior = band(version, 1, 1, null, 1_100L);
        missingTaxBehavior.setTaxBehavior(null);
        given(snapshotReader.readActive(BillingProductKind.PLAN, "FULL", EntitlementScopeKind.USER, now))
                .willReturn(java.util.Optional.of(new BillingPriceSnapshotReader.Candidate(
                        version, List.of(missingTaxBehavior))));

        assertThat(selector.selectNow(BillingProductKind.PLAN, "FULL", EntitlementScopeKind.USER)).isEmpty();
    }

    private static BillingPriceVersionEntity version() {
        BillingPriceVersionEntity entity = BillingPriceVersionEntity.builder()
                .productKind(BillingProductKind.PLAN).productKey("FULL")
                .scopeKind(EntitlementScopeKind.USER).catalogRevision("2026-09")
                .revisionNo(1L).status(BillingPriceVersionStatus.ACTIVE)
                .effectiveFrom(LocalDateTime.of(2026, 9, 1, 0, 0))
                .creationSource(BillingPriceCreationSource.SYSTEM_BACKFILL).build();
        entity.setId(UUID.randomUUID());
        return entity;
    }

    private static BillingPriceBandVersionEntity band(
            BillingPriceVersionEntity version, long total, String stripePriceRef) {
        BillingPriceBandVersionEntity entity = band(version, 1, 1, null, total);
        entity.setStripePriceRef(stripePriceRef);
        return entity;
    }

    private static BillingPriceBandVersionEntity band(
            BillingPriceVersionEntity version,
            int bandNo,
            int minMembers,
            Integer maxMembers,
            long total) {
        BillingPriceBandVersionEntity entity = BillingPriceBandVersionEntity.builder()
                .productKind(BillingProductKind.PLAN).productKey("FULL")
                .scopeKind(EntitlementScopeKind.USER).priceVersionId(version.getId())
                .bandNo(bandNo).minMembers(minMembers).maxMembers(maxMembers)
                .stripePriceRef("price_full_user_" + bandNo).currency("JPY")
                .inputAmount(total).taxBehavior(BillingTaxBehavior.INCLUSIVE)
                .taxCodeSnapshot("txcd_10000000").taxMasterSnapshot("{}")
                .amountExcludingTax(total * 10 / 11).taxAmount(total - total * 10 / 11)
                .taxRateBasisPoints(1000).taxNameSnapshot("消費税").includedInPrice(true)
                .amountIncludingTax(total).effectiveFrom(version.getEffectiveFrom())
                .status(BillingPriceVersionStatus.ACTIVE)
                .creationSource(BillingPriceCreationSource.SYSTEM_BACKFILL).build();
        entity.setId(UUID.randomUUID());
        return entity;
    }
}
