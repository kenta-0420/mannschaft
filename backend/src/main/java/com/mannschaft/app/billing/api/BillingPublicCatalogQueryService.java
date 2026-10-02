package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.BillingPriceBandVersionEntity;
import com.mannschaft.app.billing.BillingPriceSelector;
import com.mannschaft.app.billing.BillingProductKind;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.FeatureCatalogEntity;
import com.mannschaft.app.billing.FeatureCatalogRepository;
import com.mannschaft.app.billing.FeatureKeys;
import com.mannschaft.app.billing.PlanEntity;
import com.mannschaft.app.billing.PlanFeatureEntity;
import com.mannschaft.app.billing.PlanFeatureRepository;
import com.mannschaft.app.billing.PlanRepository;
import com.mannschaft.app.billing.api.dto.PublicAddon;
import com.mannschaft.app.billing.api.dto.PublicBillingCatalogResponse;
import com.mannschaft.app.billing.api.dto.PublicMoney;
import com.mannschaft.app.billing.api.dto.PublicPlan;
import com.mannschaft.app.billing.api.dto.PublicPriceBand;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 未認証画面へ、販売に必要な最小限の価格情報だけを返す。 */
@Service
@RequiredArgsConstructor
public class BillingPublicCatalogQueryService {

    private final PlanRepository planRepository;
    private final PlanFeatureRepository planFeatureRepository;
    private final FeatureCatalogRepository featureCatalogRepository;
    private final BillingPriceSelector priceSelector;

    public PublicBillingCatalogResponse getPublicCatalog(EntitlementScopeKind scopeKind) {
        List<FeatureCatalogEntity> features = featureCatalogRepository.findByEnabledTrueOrderBySortOrderAsc().stream()
                .filter(feature -> !feature.getFeatureKey().startsWith(FeatureKeys.CATALOG_HIDDEN_PREFIX))
                .toList();
        Map<String, FeatureCatalogEntity> featureByKey = features.stream()
                .collect(Collectors.toMap(FeatureCatalogEntity::getFeatureKey, Function.identity()));

        List<PublicPlan> plans = planRepository.findByEnabledTrueOrderBySortOrderAsc().stream()
                .map(plan -> toPublicPlan(plan, scopeKind, featureByKey))
                .toList();
        List<PublicAddon> addons = features.stream()
                .filter(feature -> Boolean.TRUE.equals(feature.getAddonAvailable()))
                .map(feature -> toPublicAddon(feature, scopeKind))
                .toList();
        return PublicBillingCatalogResponse.builder()
                .scopeKind(scopeKind.name())
                .plans(plans)
                .addons(addons)
                .build();
    }

    private PublicPlan toPublicPlan(
            PlanEntity plan,
            EntitlementScopeKind scopeKind,
            Map<String, FeatureCatalogEntity> featureByKey) {
        List<String> featureKeys = planFeatureRepository.findByPlanKey(plan.getPlanKey()).stream()
                .map(PlanFeatureEntity::getFeatureKey)
                .filter(featureByKey::containsKey)
                .sorted(Comparator.comparingInt((String key) -> featureByKey.get(key).getSortOrder())
                        .thenComparing(Function.identity()))
                .toList();
        PriceView price = "FREE".equals(plan.getPlanKey())
                ? PriceView.freeProduct()
                : paidPrice(BillingProductKind.PLAN, plan.getPlanKey(), scopeKind);
        return PublicPlan.builder()
                .planKey(plan.getPlanKey())
                .displayNameKey(plan.getDisplayNameKey())
                .descriptionKey(plan.getDescriptionKey())
                .startingMonthlyTotal(price.startingMonthlyTotal())
                .priceBands(price.priceBands())
                .quoteRequired(price.available() && scopeKind != EntitlementScopeKind.USER && !price.free())
                .available(price.available())
                .featureKeys(featureKeys)
                .build();
    }

    private PublicAddon toPublicAddon(FeatureCatalogEntity feature, EntitlementScopeKind scopeKind) {
        PriceView price = feature.getAddonPriceJpy() != null && feature.getAddonPriceJpy() == 0
                ? PriceView.freeProduct()
                : paidPrice(BillingProductKind.ADDON, feature.getFeatureKey(), scopeKind);
        return PublicAddon.builder()
                .featureKey(feature.getFeatureKey())
                .displayNameKey(feature.getDisplayNameKey())
                .descriptionKey(feature.getDescriptionKey())
                .startingMonthlyTotal(price.startingMonthlyTotal())
                .priceBands(price.priceBands())
                .quoteRequired(price.available() && scopeKind != EntitlementScopeKind.USER && !price.free())
                .available(price.available())
                .build();
    }

    private PriceView paidPrice(
            BillingProductKind productKind, String productKey, EntitlementScopeKind scopeKind) {
        Optional<BillingPriceSelector.SelectedPrice> selected =
                priceSelector.selectNow(productKind, productKey, scopeKind);
        if (selected.isEmpty()) {
            return PriceView.unavailableProduct();
        }

        List<PublicPriceBand> bands = new ArrayList<>();
        for (BillingPriceBandVersionEntity band : selected.get().bands()) {
            bands.add(PublicPriceBand.builder()
                    .minMembers(band.getMinMembers())
                    .maxMembers(band.getMaxMembers())
                    .startingMonthlyTotal(scopeKind == EntitlementScopeKind.USER ? moneyOf(band) : null)
                    .build());
        }
        PublicMoney starting = scopeKind == EntitlementScopeKind.USER
                ? selected.get().bands().stream()
                        .min(Comparator.comparingLong(BillingPriceBandVersionEntity::getAmountIncludingTax))
                        .map(BillingPublicCatalogQueryService::moneyOf)
                        .orElse(null)
                : null;
        return new PriceView(starting, List.copyOf(bands), true, false);
    }

    private static PublicMoney moneyOf(BillingPriceBandVersionEntity band) {
        return PublicMoney.builder()
                .currency(band.getCurrency())
                .amountIncludingTax(band.getAmountIncludingTax())
                .amountExcludingTax(band.getAmountExcludingTax())
                .taxAmount(band.getTaxAmount())
                .taxName(band.getTaxNameSnapshot())
                .taxRateBasisPoints(band.getTaxRateBasisPoints())
                .build();
    }

    private record PriceView(
            PublicMoney startingMonthlyTotal,
            List<PublicPriceBand> priceBands,
            boolean available,
            boolean free) {

        private static PriceView freeProduct() {
            return new PriceView(null, List.of(), true, true);
        }

        private static PriceView unavailableProduct() {
            return new PriceView(null, List.of(), false, false);
        }
    }
}
