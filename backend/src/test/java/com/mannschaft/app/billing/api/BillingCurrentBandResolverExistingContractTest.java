package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.BillingContractEntity;
import com.mannschaft.app.billing.BillingPriceBandVersionEntity;
import com.mannschaft.app.billing.BillingPriceBandVersionRepository;
import com.mannschaft.app.billing.BillingPriceVersionEntity;
import com.mannschaft.app.billing.BillingPriceVersionRepository;
import com.mannschaft.app.billing.BillingPriceVersionStatus;
import com.mannschaft.app.billing.BillingProductKind;
import com.mannschaft.app.billing.ContractKind;
import com.mannschaft.app.billing.ContractStatus;
import com.mannschaft.app.billing.EntitlementScopeKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * H群 AC-124・AC-126: 既存契約は band 境界を跨いでも・月末昇格が起きても、
 * 次周期まで {@code price_band_version_id} snapshot の旧価格を維持する
 * （{@link BillingCurrentBandResolver#resolveContractBand} の現行実装どおり）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AC-124/AC-126: 既存契約は次周期まで旧bandを維持する")
class BillingCurrentBandResolverExistingContractTest {

    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @Mock private BillingPriceVersionRepository priceVersionRepository;
    @Mock private BillingPriceBandVersionRepository bandRepository;

    private BillingCurrentBandResolver resolver;
    private BillingPriceVersionEntity revision;
    private BillingPriceBandVersionEntity oldBand;
    private BillingPriceBandVersionEntity newBandForIncreasedCount;

    @BeforeEach
    void setUp() {
        resolver = new BillingCurrentBandResolver(priceVersionRepository, bandRepository);
        revision = revision();
        oldBand = band(1, 1, 20, 1_100L);
        newBandForIncreasedCount = band(2, 21, 50, 2_200L);
    }

    @Test
    @DisplayName("AC-124: 契約は人数がbandを跨いでも当期のsnapshot bandを維持し、"
            + "新bandはresolveCurrentBand（次の見積り）でのみ選ばれる")
    void existingContractKeepsSnapshotBandAcrossBoundaryCrossing() {
        BillingContractEntity contract = contract(oldBand.getId());

        var contractBand = resolver.resolveContractBand(contract, 21, NOW);

        assertThat(contractBand).get().isEqualTo(oldBand);
        // snapshot直引きのため、現行revision検索は一切呼ばれない（当期金額は変わらない）。
        verifyNoInteractions(priceVersionRepository);
    }

    @Test
    @DisplayName("AC-124: 人数が増えて境界を跨いだ後にresolveCurrentBandを呼ぶと"
            + "次の見積りでは新bandが選ばれる")
    void nextQuoteSelectsNewBandAfterBoundaryCrossing() {
        given(priceVersionRepository.findEffectiveCandidates(
                BillingProductKind.PLAN, "FULL", EntitlementScopeKind.USER,
                List.of(BillingPriceVersionStatus.ACTIVE), NOW))
                .willReturn(List.of(revision));
        given(bandRepository.findByPriceVersionIdAndDeletedAtIsNullOrderByBandNoAsc(revision.getId()))
                .willReturn(List.of(oldBand, newBandForIncreasedCount));

        assertThat(resolver.resolveCurrentBand(
                BillingProductKind.PLAN, "FULL", EntitlementScopeKind.USER, 21, NOW))
                .get().isEqualTo(newBandForIncreasedCount);
    }

    @Test
    @DisplayName("AC-126: 月末昇格でsnapshot bandがRETIREDになっても、"
            + "既存契約は次周期まで同じbandを維持する（当期金額は変わらない）")
    void existingContractKeepsRetiredSnapshotBandUntilNextPeriod() {
        oldBand.setStatus(BillingPriceVersionStatus.RETIRED);
        given(bandRepository.findByIdAndDeletedAtIsNull(oldBand.getId()))
                .willReturn(java.util.Optional.of(oldBand));
        BillingContractEntity contract = contract(oldBand.getId());

        var contractBand = resolver.resolveContractBand(contract, 5, NOW);

        assertThat(contractBand).get().isEqualTo(oldBand);
        assertThat(contractBand.get().getStatus()).isEqualTo(BillingPriceVersionStatus.RETIRED);
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

    private BillingContractEntity contract(UUID priceBandVersionId) {
        BillingContractEntity contract = BillingContractEntity.builder()
                .scopeKind(EntitlementScopeKind.USER).scopeId(1L)
                .contractKind(ContractKind.PLAN).planKey("FULL")
                .status(ContractStatus.ACTIVE)
                .priceJpySnapshot(1_100)
                .memberCountSnapshot(1)
                .priceBandVersionId(priceBandVersionId)
                .billingCustomerId(UUID.randomUUID())
                .pspSubscriptionRef("sub_ut")
                .currentPeriodEnd(LocalDateTime.ofInstant(NOW.plusSeconds(20L * 86_400), ZoneOffset.UTC))
                .version(0L)
                .build();
        contract.setId(UUID.randomUUID());
        return contract;
    }
}
