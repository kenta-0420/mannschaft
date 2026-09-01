package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.BillingPriceBandVersionEntity;
import com.mannschaft.app.billing.BillingPriceCreationSource;
import com.mannschaft.app.billing.BillingPriceSelector;
import com.mannschaft.app.billing.BillingPriceVersionEntity;
import com.mannschaft.app.billing.BillingPriceVersionStatus;
import com.mannschaft.app.billing.BillingProductKind;
import com.mannschaft.app.billing.BillingTaxBehavior;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.FeatureCatalogEntity;
import com.mannschaft.app.billing.FeatureCatalogRepository;
import com.mannschaft.app.billing.FeatureCategory;
import com.mannschaft.app.billing.PlanEntity;
import com.mannschaft.app.billing.PlanFeatureEntity;
import com.mannschaft.app.billing.PlanFeatureRepository;
import com.mannschaft.app.billing.PlanRepository;
import com.mannschaft.app.billing.api.dto.PublicBillingCatalogResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("公開価格カタログ組立")
class BillingPublicCatalogQueryServiceTest {

    @Mock private PlanRepository planRepository;
    @Mock private PlanFeatureRepository planFeatureRepository;
    @Mock private FeatureCatalogRepository featureCatalogRepository;
    @Mock private BillingPriceSelector priceSelector;

    @Test
    @DisplayName("USERは税込主表示と税内訳を返し、価格未準備の有償商品は準備中にする")
    void userShowsTaxInclusivePriceAndUnavailableFailClosed() {
        PlanEntity full = plan("FULL", 2_000);
        FeatureCatalogEntity feature = feature("calendar.full", false, null);
        given(planRepository.findByEnabledTrueOrderBySortOrderAsc()).willReturn(List.of(full));
        given(featureCatalogRepository.findByEnabledTrueOrderBySortOrderAsc()).willReturn(List.of(feature));
        given(planFeatureRepository.findByPlanKey("FULL"))
                .willReturn(List.of(PlanFeatureEntity.builder().planKey("FULL")
                        .featureKey("calendar.full").build()));
        given(priceSelector.selectNow(BillingProductKind.PLAN, "FULL", EntitlementScopeKind.USER))
                .willReturn(Optional.of(selectedPrice(EntitlementScopeKind.USER, 1_100L)));

        PublicBillingCatalogResponse response = service().getPublicCatalog(EntitlementScopeKind.USER);

        assertThat(response.getPlans()).singleElement().satisfies(plan -> {
            assertThat(plan.isAvailable()).isTrue();
            assertThat(plan.isQuoteRequired()).isFalse();
            assertThat(plan.getStartingMonthlyTotal().getAmountIncludingTax()).isEqualTo(1_100L);
            assertThat(plan.getStartingMonthlyTotal().getAmountExcludingTax()).isEqualTo(1_000L);
            assertThat(plan.getStartingMonthlyTotal().getTaxAmount()).isEqualTo(100L);
            assertThat(plan.getFeatureKeys()).containsExactly("calendar.full");
        });
    }

    @Test
    @DisplayName("TEAMは人数帯だけを返して金額を秘匿し、ログイン後見積りを要求する")
    void teamHidesAmountsAndRequiresQuote() {
        PlanEntity full = plan("FULL", 2_000);
        given(planRepository.findByEnabledTrueOrderBySortOrderAsc()).willReturn(List.of(full));
        given(featureCatalogRepository.findByEnabledTrueOrderBySortOrderAsc()).willReturn(List.of());
        given(planFeatureRepository.findByPlanKey("FULL")).willReturn(List.of());
        given(priceSelector.selectNow(BillingProductKind.PLAN, "FULL", EntitlementScopeKind.TEAM))
                .willReturn(Optional.of(selectedPrice(EntitlementScopeKind.TEAM, 3_300L)));

        PublicBillingCatalogResponse response = service().getPublicCatalog(EntitlementScopeKind.TEAM);

        assertThat(response.getPlans()).singleElement().satisfies(plan -> {
            assertThat(plan.isAvailable()).isTrue();
            assertThat(plan.isQuoteRequired()).isTrue();
            assertThat(plan.getStartingMonthlyTotal()).isNull();
            assertThat(plan.getPriceBands()).singleElement()
                    .satisfies(band -> assertThat(band.getStartingMonthlyTotal()).isNull());
        });
    }

    @Test
    @DisplayName("無料商品は0円価格版を作らず利用可能、有償価格未準備は全価格nullで販売不可")
    void distinguishesFreeProductFromUnprovisionedPaidProduct() {
        PlanEntity free = plan("FREE", 0);
        PlanEntity undecided = plan("BASIC", null);
        PlanEntity full = plan("FULL", 2_000);
        FeatureCatalogEntity freeAddon = feature("free.addon", true, 0);
        FeatureCatalogEntity undecidedAddon = feature("undecided.addon", true, null);
        FeatureCatalogEntity paidAddon = feature("paid.addon", true, 500);
        given(planRepository.findByEnabledTrueOrderBySortOrderAsc()).willReturn(List.of(free, undecided, full));
        given(featureCatalogRepository.findByEnabledTrueOrderBySortOrderAsc())
                .willReturn(List.of(freeAddon, undecidedAddon, paidAddon));
        given(planFeatureRepository.findByPlanKey("FREE")).willReturn(List.of());
        given(planFeatureRepository.findByPlanKey("BASIC")).willReturn(List.of());
        given(planFeatureRepository.findByPlanKey("FULL")).willReturn(List.of());
        given(priceSelector.selectNow(BillingProductKind.PLAN, "BASIC", EntitlementScopeKind.USER))
                .willReturn(Optional.empty());
        given(priceSelector.selectNow(BillingProductKind.PLAN, "FULL", EntitlementScopeKind.USER))
                .willReturn(Optional.empty());
        given(priceSelector.selectNow(BillingProductKind.ADDON, "undecided.addon", EntitlementScopeKind.USER))
                .willReturn(Optional.empty());
        given(priceSelector.selectNow(BillingProductKind.ADDON, "paid.addon", EntitlementScopeKind.USER))
                .willReturn(Optional.empty());

        PublicBillingCatalogResponse response = service().getPublicCatalog(EntitlementScopeKind.USER);

        assertThat(response.getPlans()).extracting("planKey", "available", "startingMonthlyTotal")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("FREE", true, null),
                        org.assertj.core.groups.Tuple.tuple("BASIC", false, null),
                        org.assertj.core.groups.Tuple.tuple("FULL", false, null));
        assertThat(response.getAddons()).extracting("featureKey", "available", "startingMonthlyTotal")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("free.addon", true, null),
                        org.assertj.core.groups.Tuple.tuple("undecided.addon", false, null),
                        org.assertj.core.groups.Tuple.tuple("paid.addon", false, null));
    }

    private BillingPublicCatalogQueryService service() {
        return new BillingPublicCatalogQueryService(
                planRepository, planFeatureRepository, featureCatalogRepository, priceSelector);
    }

    private static PlanEntity plan(String key, Integer legacyPrice) {
        return PlanEntity.builder().planKey(key).displayNameKey("plan." + key)
                .descriptionKey("plan." + key + ".description")
                .baseMonthlyPriceJpy(legacyPrice).sortOrder(1).enabled(true).build();
    }

    private static FeatureCatalogEntity feature(String key, boolean addon, Integer legacyPrice) {
        return FeatureCatalogEntity.builder().featureKey(key).category(FeatureCategory.INTERNAL)
                .addonAvailable(addon).addonPriceJpy(legacyPrice).freeForNonprofit(false)
                .displayNameKey("feature." + key).descriptionKey("feature." + key + ".description")
                .sortOrder(1).enabled(true).build();
    }

    private static BillingPriceSelector.SelectedPrice selectedPrice(
            EntitlementScopeKind scopeKind, long amountIncludingTax) {
        BillingPriceVersionEntity version = BillingPriceVersionEntity.builder()
                .productKind(BillingProductKind.PLAN).productKey("FULL").scopeKind(scopeKind)
                .catalogRevision("2026-09").revisionNo(1L).status(BillingPriceVersionStatus.ACTIVE)
                .effectiveFrom(Instant.parse("2026-09-01T00:00:00Z"))
                .creationSource(BillingPriceCreationSource.SYSTEM_BACKFILL).build();
        version.setId(UUID.randomUUID());
        BillingPriceBandVersionEntity band = BillingPriceBandVersionEntity.builder()
                .productKind(BillingProductKind.PLAN).productKey("FULL").scopeKind(scopeKind)
                .priceVersionId(version.getId()).bandNo(1).minMembers(1).maxMembers(20)
                .stripePriceRef("price_full").currency("JPY").inputAmount(amountIncludingTax)
                .taxBehavior(BillingTaxBehavior.INCLUSIVE).taxCodeSnapshot("txcd_10000000")
                .taxMasterSnapshot("{}").amountExcludingTax(amountIncludingTax - 100)
                .taxAmount(100L).taxRateBasisPoints(1000).taxNameSnapshot("消費税")
                .includedInPrice(true).amountIncludingTax(amountIncludingTax)
                .effectiveFrom(version.getEffectiveFrom()).status(BillingPriceVersionStatus.ACTIVE)
                .creationSource(BillingPriceCreationSource.SYSTEM_BACKFILL).build();
        band.setId(UUID.randomUUID());
        return new BillingPriceSelector.SelectedPrice(version, List.of(band));
    }
}
