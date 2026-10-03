package com.mannschaft.app.billing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * H群 AC-121・AC-122: BC-18/BC-24 の「23:55 snapshot → 00:00 に B へ切り替わる」
 * および「scheduler が遅延しても、公開 pricing/quote 共用の now selector が
 * {@code promoteDue} を呼び、次回参照時に正しい revision を返す」を、
 * {@link BillingPriceSelector}（now selector）と実物の {@link BillingPricePromotionService}・
 * {@link BillingPriceSnapshotReader} を組み合わせて固定する。
 *
 * <p>既存の {@code BillingPriceSelectorTest} は promotionService をモック化しており、
 * 実際に昇格が起きた後に snapshot reader が正しい revision を返すという結線までは
 * 検証していない。既存の {@code BillingPricePromotionServiceTest} も単体では
 * 「23:55 はまだ昇格しない」ことまでは観測しない。リポジトリは可変な内部状態を持つ
 * フェイクとして振る舞わせ、実際の DB 昇格を模した状態遷移を再現する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AC-121/AC-122: 月境界の昇格とscheduler遅延時のlazy-promote")
class BillingPriceMonthBoundaryPromotionTest {

    private static final Instant BEFORE_BOUNDARY = Instant.parse("2026-08-31T23:55:00Z");
    private static final Instant AT_BOUNDARY = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant AFTER_DELAY = Instant.parse("2026-09-01T00:30:00Z");

    @Mock private BillingPriceVersionRepository priceVersionRepository;
    @Mock private BillingPriceBandVersionRepository bandRepository;

    private BillingPriceVersionEntity revisionA;
    private BillingPriceVersionEntity revisionB;
    private BillingPriceBandVersionEntity bandA;
    private BillingPriceBandVersionEntity bandB;

    @BeforeEach
    void setUp() {
        revisionA = version(BillingPriceVersionStatus.ACTIVE,
                Instant.parse("2026-08-01T00:00:00Z"), AT_BOUNDARY);
        revisionB = version(BillingPriceVersionStatus.SCHEDULED, AT_BOUNDARY, null);
        bandA = band(revisionA, BillingPriceVersionStatus.ACTIVE, 1_100L);
        bandB = band(revisionB, BillingPriceVersionStatus.SCHEDULED, 1_500L);

        // findEffectiveCandidates / findByPriceVersionId... は現在の status を都度見て絞り込む
        // フェイクとして振る舞わせる（実 DB の永続化状態遷移を模す）。
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
                .thenReturn(List.of(bandA));
        lenient().when(bandRepository.findAllByPriceVersionIdForUpdate(revisionB.getId()))
                .thenReturn(List.of(bandB));
        lenient().when(bandRepository.findByPriceVersionIdAndDeletedAtIsNullOrderByBandNoAsc(revisionA.getId()))
                .thenReturn(List.of(bandA));
        lenient().when(bandRepository.findByPriceVersionIdAndDeletedAtIsNullOrderByBandNoAsc(revisionB.getId()))
                .thenReturn(List.of(bandB));
    }

    @Test
    @DisplayName("AC-121: 前月末23:55はAのまま、00:00になるとBへ切り替わる（固定Clockで観測）")
    void switchesFromAToBAtMonthBoundary() {
        BillingPriceSelector selectorBeforeBoundary = selectorAt(BEFORE_BOUNDARY);
        assertThat(selectorBeforeBoundary.selectNow(BillingProductKind.PLAN, "FULL", EntitlementScopeKind.USER))
                .get().extracting(s -> s.bands().getFirst().getAmountIncludingTax()).isEqualTo(1_100L);
        assertThat(revisionA.getStatus()).isEqualTo(BillingPriceVersionStatus.ACTIVE);
        assertThat(revisionB.getStatus()).isEqualTo(BillingPriceVersionStatus.SCHEDULED);

        BillingPriceSelector selectorAtBoundary = selectorAt(AT_BOUNDARY);
        assertThat(selectorAtBoundary.selectNow(BillingProductKind.PLAN, "FULL", EntitlementScopeKind.USER))
                .get().extracting(s -> s.bands().getFirst().getAmountIncludingTax()).isEqualTo(1_500L);
        assertThat(revisionA.getStatus()).isEqualTo(BillingPriceVersionStatus.RETIRED);
        assertThat(revisionB.getStatus()).isEqualTo(BillingPriceVersionStatus.ACTIVE);
    }

    @Test
    @DisplayName("AC-122: scheduler未実行のままeffectiveFromを過ぎても、"
            + "now selectorへの参照自体がlazy-promoteし正しいrevisionを返す")
    void lazyPromotesOnDelayedSchedulerAndReturnsCorrectRevision() {
        // 昇格バッチは一度も走っていない想定（BEFORE_BOUNDARYでの参照すら発生していない）。
        BillingPriceSelector delayedSelector = selectorAt(AFTER_DELAY);

        var selected = delayedSelector.selectNow(BillingProductKind.PLAN, "FULL", EntitlementScopeKind.USER);

        assertThat(selected).get()
                .extracting(s -> s.bands().getFirst().getAmountIncludingTax()).isEqualTo(1_500L);
        assertThat(revisionB.getStatus()).isEqualTo(BillingPriceVersionStatus.ACTIVE);

        // 次回参照でも同じ正しいrevisionを返し続ける（冪等）。
        var secondCall = selectorAt(AFTER_DELAY)
                .selectNow(BillingProductKind.PLAN, "FULL", EntitlementScopeKind.USER);
        assertThat(secondCall).get()
                .extracting(s -> s.bands().getFirst().getAmountIncludingTax()).isEqualTo(1_500L);
    }

    private BillingPriceSelector selectorAt(Instant now) {
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        BillingPricePromotionService promotionService =
                new BillingPricePromotionService(priceVersionRepository, bandRepository);
        BillingPriceSnapshotReader snapshotReader =
                new BillingPriceSnapshotReader(priceVersionRepository, bandRepository);
        return new BillingPriceSelector(promotionService, snapshotReader, clock);
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
            BillingPriceVersionEntity revision, BillingPriceVersionStatus status, long amount) {
        BillingPriceBandVersionEntity entity = BillingPriceBandVersionEntity.builder()
                .productKind(revision.getProductKind()).productKey(revision.getProductKey())
                .scopeKind(revision.getScopeKind()).priceVersionId(revision.getId())
                .bandNo(1).minMembers(1).maxMembers(null)
                .stripePriceRef("price_full_1").currency("JPY")
                .inputAmount(amount).taxBehavior(BillingTaxBehavior.INCLUSIVE)
                .taxCodeSnapshot("txcd_10000000").taxMasterSnapshot("{}")
                .amountExcludingTax(amount * 10 / 11).taxAmount(amount - amount * 10 / 11)
                .taxRateBasisPoints(1000).taxNameSnapshot("消費税").includedInPrice(true)
                .amountIncludingTax(amount)
                .effectiveFrom(revision.getEffectiveFrom()).effectiveUntil(revision.getEffectiveUntil())
                .status(status)
                .build();
        entity.setId(UUID.randomUUID());
        return entity;
    }
}
