package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.BillingPriceBandVersionEntity;
import com.mannschaft.app.billing.BillingPriceBandVersionRepository;
import com.mannschaft.app.billing.BillingPriceVersionEntity;
import com.mannschaft.app.billing.BillingPriceVersionRepository;
import com.mannschaft.app.billing.BillingPriceVersionStatus;
import com.mannschaft.app.billing.BillingProductKind;
import com.mannschaft.app.billing.EntitlementScopeKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * H群 AC-123: {@link BillingCurrentBandResolver#resolveCurrentBand} が
 * BC-24 の flat/20-21/50-51 境界（人数 20 と 21、50 と 51）で正しい band を選ぶことを固定する。
 *
 * <p>既存の {@code BillingPriceSelectorTest#acceptsTwentyTwentyOneAndFiftyFiftyOneBoundaries} は
 * {@code BillingPriceSelector.isSellable} の販売可否判定を検証するものであり、band 選択の
 * 唯一の正である {@link BillingCurrentBandResolver} を通した専用テストは存在しなかった
 * （第4陣調査・本陣で再確認済み）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AC-123: BillingCurrentBandResolverのband境界選択（20/21・50/51）")
class BillingCurrentBandResolverBoundaryTest {

    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @Mock private BillingPriceVersionRepository priceVersionRepository;
    @Mock private BillingPriceBandVersionRepository bandRepository;

    private BillingCurrentBandResolver resolver;
    private BillingPriceVersionEntity revision;
    private BillingPriceBandVersionEntity flatBand;
    private BillingPriceBandVersionEntity midBand;
    private BillingPriceBandVersionEntity topBand;

    @BeforeEach
    void setUp() {
        resolver = new BillingCurrentBandResolver(priceVersionRepository, bandRepository);
        revision = revision();
        flatBand = band(1, 1, 20, 1_100L);
        midBand = band(2, 21, 50, 2_200L);
        topBand = band(3, 51, null, 3_300L);

        given(priceVersionRepository.findEffectiveCandidates(
                BillingProductKind.PLAN, "FULL", EntitlementScopeKind.USER,
                List.of(BillingPriceVersionStatus.ACTIVE), NOW))
                .willReturn(List.of(revision));
        given(bandRepository.findByPriceVersionIdAndDeletedAtIsNullOrderByBandNoAsc(revision.getId()))
                .willReturn(List.of(flatBand, midBand, topBand));
    }

    @Test
    @DisplayName("人数20はflat band(1-20)を選ぶ")
    void memberCount20SelectsFlatBand() {
        assertThat(resolver.resolveCurrentBand(
                BillingProductKind.PLAN, "FULL", EntitlementScopeKind.USER, 20, NOW))
                .get().isEqualTo(flatBand);
    }

    @Test
    @DisplayName("人数21は境界を跨ぎ20-21bandを選ぶ")
    void memberCount21SelectsMidBand() {
        assertThat(resolver.resolveCurrentBand(
                BillingProductKind.PLAN, "FULL", EntitlementScopeKind.USER, 21, NOW))
                .get().isEqualTo(midBand);
    }

    @Test
    @DisplayName("人数50は20-21band(21-50)を選ぶ")
    void memberCount50SelectsMidBand() {
        assertThat(resolver.resolveCurrentBand(
                BillingProductKind.PLAN, "FULL", EntitlementScopeKind.USER, 50, NOW))
                .get().isEqualTo(midBand);
    }

    @Test
    @DisplayName("人数51は境界を跨ぎ50-51band(51以上)を選ぶ")
    void memberCount51SelectsTopBand() {
        assertThat(resolver.resolveCurrentBand(
                BillingProductKind.PLAN, "FULL", EntitlementScopeKind.USER, 51, NOW))
                .get().isEqualTo(topBand);
    }

    private static BillingPriceVersionEntity revision() {
        BillingPriceVersionEntity entity = BillingPriceVersionEntity.builder()
                .productKind(BillingProductKind.PLAN).productKey("FULL")
                .scopeKind(EntitlementScopeKind.USER)
                .status(BillingPriceVersionStatus.ACTIVE)
                .effectiveFrom(NOW.minusSeconds(3600))
                .build();
        entity.setId(UUID.randomUUID());
        return entity;
    }

    private BillingPriceBandVersionEntity band(int bandNo, int minMembers, Integer maxMembers, long amount) {
        BillingPriceBandVersionEntity entity = BillingPriceBandVersionEntity.builder()
                .productKind(BillingProductKind.PLAN).productKey("FULL")
                .scopeKind(EntitlementScopeKind.USER)
                .priceVersionId(revision.getId())
                .bandNo(bandNo).minMembers(minMembers).maxMembers(maxMembers)
                .stripePriceRef("price_full_" + bandNo).currency("JPY")
                .amountIncludingTax(amount)
                .effectiveFrom(revision.getEffectiveFrom())
                .status(BillingPriceVersionStatus.ACTIVE)
                .build();
        entity.setId(UUID.randomUUID());
        return entity;
    }
}
