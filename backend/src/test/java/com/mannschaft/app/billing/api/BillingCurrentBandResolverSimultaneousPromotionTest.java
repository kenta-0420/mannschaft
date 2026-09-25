package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.BillingPriceBandVersionEntity;
import com.mannschaft.app.billing.BillingPriceBandVersionRepository;
import com.mannschaft.app.billing.BillingPricePromotionService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * H群 AC-125: band 切替（旧 revision の RETIRED 化）と月末の昇格が同時に起きても、
 * {@link BillingCurrentBandResolver#resolveCurrentBand} が常にちょうど1つの
 * ACTIVE revision の band を返すことを固定する。
 *
 * <p>実物の {@link BillingPricePromotionService} で A→RETIRED / B→ACTIVE の昇格を行った
 * その直後に、新revision B 配下で人数band境界（20/21）を跨ぐ2つの人数で
 * {@code resolveCurrentBand} を呼び、いずれも旧revision Aへ迷い込まず・
 * 複数ACTIVEに引っかからずちょうど1件だけ解決できることを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AC-125: band切替と月末昇格が同時でもresolveCurrentBandは常に1件を返す")
class BillingCurrentBandResolverSimultaneousPromotionTest {

    private static final Instant BOUNDARY = Instant.parse("2026-09-01T00:00:00Z");

    @Mock private BillingPriceVersionRepository priceVersionRepository;
    @Mock private BillingPriceBandVersionRepository bandRepository;

    private BillingPriceVersionEntity revisionA;
    private BillingPriceVersionEntity revisionB;
    private BillingPriceBandVersionEntity bandAFlat;
    private BillingPriceBandVersionEntity bandBFlat;
    private BillingPriceBandVersionEntity bandBMid;

    @BeforeEach
    void setUp() {
        revisionA = version(BillingPriceVersionStatus.ACTIVE,
                Instant.parse("2026-08-01T00:00:00Z"), BOUNDARY);
        revisionB = version(BillingPriceVersionStatus.SCHEDULED, BOUNDARY, null);
        bandAFlat = band(revisionA, BillingPriceVersionStatus.ACTIVE, 1, null, 1_100L);
        bandBFlat = band(revisionB, BillingPriceVersionStatus.SCHEDULED, 1, 20, 1_500L);
        bandBMid = band(revisionB, BillingPriceVersionStatus.SCHEDULED, 21, 50, 2_500L);

        lenient().when(priceVersionRepository.findEffectiveCandidates(
                any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            List<BillingPriceVersionStatus> statuses = invocation.getArgument(3);
            Instant at = invocation.getArgument(4);
            return List.of(revisionA, revisionB).stream()
                    .filter(v -> statuses.contains(v.getStatus()))
                    .filter(v -> isEffectiveAt(v.getEffectiveFrom(), v.getEffectiveUntil(), at))
                    .toList();
        });
        lenient().when(priceVersionRepository.findAllForUpdate(any(), any(), any()))
                .thenReturn(List.of(revisionA, revisionB));
        lenient().when(bandRepository.findAllByPriceVersionIdForUpdate(revisionA.getId()))
                .thenReturn(List.of(bandAFlat));
        lenient().when(bandRepository.findAllByPriceVersionIdForUpdate(revisionB.getId()))
                .thenReturn(List.of(bandBFlat, bandBMid));
        lenient().when(bandRepository.findByPriceVersionIdAndDeletedAtIsNullOrderByBandNoAsc(revisionB.getId()))
                .thenReturn(List.of(bandBFlat, bandBMid));
    }

    @Test
    @DisplayName("昇格直後、境界を跨ぐ2人数(20/21)いずれもrevision Bの中でちょうど1band")
    void resolvesExactlyOneActiveBandAcrossBoundaryImmediatelyAfterPromotion() {
        BillingPricePromotionService promotionService =
                new BillingPricePromotionService(priceVersionRepository, bandRepository);
        assertThat(promotionService.promoteDue(
                BillingProductKind.PLAN, "FULL", EntitlementScopeKind.USER, BOUNDARY)).isTrue();
        assertThat(revisionA.getStatus()).isEqualTo(BillingPriceVersionStatus.RETIRED);
        assertThat(revisionB.getStatus()).isEqualTo(BillingPriceVersionStatus.ACTIVE);

        BillingCurrentBandResolver resolver =
                new BillingCurrentBandResolver(priceVersionRepository, bandRepository);

        var atTwenty = resolver.resolveCurrentBand(
                BillingProductKind.PLAN, "FULL", EntitlementScopeKind.USER, 20, BOUNDARY);
        var atTwentyOne = resolver.resolveCurrentBand(
                BillingProductKind.PLAN, "FULL", EntitlementScopeKind.USER, 21, BOUNDARY);

        assertThat(atTwenty).get().isEqualTo(bandBFlat);
        assertThat(atTwentyOne).get().isEqualTo(bandBMid);
        // 旧revision Aのbandへは決して解決されない。
        assertThat(atTwenty).get().isNotEqualTo(bandAFlat);
        assertThat(atTwentyOne).get().isNotEqualTo(bandAFlat);
    }

    private static boolean isEffectiveAt(Instant from, Instant until, Instant at) {
        return !from.isAfter(at) && (until == null || at.isBefore(until));
    }

    private static BillingPriceVersionEntity version(
            BillingPriceVersionStatus status, Instant from, Instant until) {
        BillingPriceVersionEntity entity = BillingPriceVersionEntity.builder()
                .productKind(BillingProductKind.PLAN).productKey("FULL")
                .scopeKind(EntitlementScopeKind.USER)
                .status(status).effectiveFrom(from).effectiveUntil(until)
                .build();
        entity.setId(UUID.randomUUID());
        return entity;
    }

    private static BillingPriceBandVersionEntity band(
            BillingPriceVersionEntity revision, BillingPriceVersionStatus status,
            int minMembers, Integer maxMembers, long amount) {
        BillingPriceBandVersionEntity entity = BillingPriceBandVersionEntity.builder()
                .productKind(revision.getProductKind()).productKey(revision.getProductKey())
                .scopeKind(revision.getScopeKind()).priceVersionId(revision.getId())
                .bandNo(minMembers).minMembers(minMembers).maxMembers(maxMembers)
                .stripePriceRef("price_full_" + minMembers).currency("JPY")
                .amountIncludingTax(amount)
                .effectiveFrom(revision.getEffectiveFrom()).effectiveUntil(revision.getEffectiveUntil())
                .status(status)
                .build();
        entity.setId(UUID.randomUUID());
        return entity;
    }
}
