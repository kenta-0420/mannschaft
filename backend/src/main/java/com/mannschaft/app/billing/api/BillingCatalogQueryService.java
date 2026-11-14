package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.FeatureCatalogEntity;
import com.mannschaft.app.billing.FeatureCatalogRepository;
import com.mannschaft.app.billing.FeatureKeys;
import com.mannschaft.app.billing.BillingPriceSelector;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.LegacyPlanPriceMapper;
import com.mannschaft.app.billing.PlanEntity;
import com.mannschaft.app.billing.PlanFeatureEntity;
import com.mannschaft.app.billing.PlanFeatureRepository;
import com.mannschaft.app.billing.PlanPriceBandScopeKind;
import com.mannschaft.app.billing.PlanRepository;
import com.mannschaft.app.billing.api.dto.FeatureItem;
import com.mannschaft.app.billing.api.dto.PlanCatalogResponse;
import com.mannschaft.app.billing.api.dto.PlanItem;
import com.mannschaft.app.billing.api.dto.PriceBand;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * F20.1: プランカタログ（利用者向け・読み取り）の組み立て（設計書 02 §2.1）。
 *
 * <p>{@code enabled=false} のプラン・機能は返さない。{@code legacy.} プレフィックスの機能は
 * 内部ブリッジ用途のためカタログ表示から除外する（{@link FeatureKeys#CATALOG_HIDDEN_PREFIX}）。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BillingCatalogQueryService {

    private final PlanRepository planRepository;
    private final PlanFeatureRepository planFeatureRepository;
    private final BillingPriceSelector priceSelector;
    private final LegacyPlanPriceMapper priceMapper;
    private final FeatureCatalogRepository featureCatalogRepository;

    /** プランカタログを組み立てて返す（sort_order 昇順・enabled のみ）。 */
    public PlanCatalogResponse getCatalog() {
        List<PlanEntity> plans = planRepository.findByEnabledTrueOrderBySortOrderAsc();
        Set<BillingPriceSelector.PlanScopeKey> keys = plans.stream().flatMap(plan ->
                java.util.Arrays.stream(EntitlementScopeKind.values())
                        .map(scope -> new BillingPriceSelector.PlanScopeKey(plan.getPlanKey(), scope)))
                .collect(Collectors.toSet());
        Map<BillingPriceSelector.PlanScopeKey, BillingPriceSelector.LegacyPlanSelection> selections =
                priceSelector.selectLegacyPlans(keys);
        List<PlanItem> items = new ArrayList<>();
        for (PlanEntity plan : plans) {
            items.add(PlanItem.builder()
                    .planKey(plan.getPlanKey())
                    .displayNameKey(plan.getDisplayNameKey())
                    .descriptionKey(plan.getDescriptionKey())
                    .baseMonthlyPriceJpy(basePriceOfPlan(plan.getPlanKey(), selections))
                    .features(featuresOfPlan(plan.getPlanKey()))
                    .priceBands(priceBandsOfPlan(plan.getPlanKey(), selections))
                    .build());
        }
        return PlanCatalogResponse.builder().plans(items).build();
    }

    /** プランに紐づく機能を FeatureItem に解決する（enabled・legacy 除外・sort_order 昇順）。 */
    private List<FeatureItem> featuresOfPlan(String planKey) {
        List<FeatureItem> result = new ArrayList<>();
        for (PlanFeatureEntity pf : planFeatureRepository.findByPlanKey(planKey)) {
            FeatureCatalogEntity feature = featureCatalogRepository.findById(pf.getFeatureKey()).orElse(null);
            if (feature == null || !Boolean.TRUE.equals(feature.getEnabled())) {
                continue; // enabled=false / 未登録は表示しない（fail-safe）。
            }
            if (feature.getFeatureKey().startsWith(FeatureKeys.CATALOG_HIDDEN_PREFIX)) {
                continue; // legacy.* は内部ブリッジ用途で除外。
            }
            result.add(toFeatureItem(feature));
        }
        result.sort((a, b) -> Integer.compare(a.getSortOrder(), b.getSortOrder()));
        return result;
    }

    private Integer basePriceOfPlan(String planKey,
            Map<BillingPriceSelector.PlanScopeKey, BillingPriceSelector.LegacyPlanSelection> selections) {
        BillingPriceSelector.LegacyPlanSelection selection = selections.get(
                new BillingPriceSelector.PlanScopeKey(planKey, EntitlementScopeKind.USER));
        if (selection == null) {
            return null;
        }
        if ("FREE".equals(planKey)) {
            return selection.oldPrice() == null ? null : selection.oldPrice().baseMonthlyPriceJpy();
        }
        if (selection.state() == BillingPriceSelector.LegacyPlanSelectionState.SELECTED) {
            List<BillingPriceSelector.LegacyPlanBandSnapshot> matching = selection.snapshot().bands().stream()
                    .filter(band -> band.minMembers() != null && band.minMembers() <= 1
                            && (band.maxMembers() == null || band.maxMembers() >= 1)).toList();
            return matching.size() == 1 ? priceMapper.toCatalogPriceJpy(matching.getFirst().amountIncludingTax()) : null;
        }
        return selection.state() == BillingPriceSelector.LegacyPlanSelectionState.NO_PUBLISHED_PRICE
                && selection.oldPrice() != null && Integer.valueOf(0).equals(selection.oldPrice().baseMonthlyPriceJpy())
                ? 0 : null;
    }

    private List<PriceBand> priceBandsOfPlan(String planKey,
            Map<BillingPriceSelector.PlanScopeKey, BillingPriceSelector.LegacyPlanSelection> selections) {
        List<PriceBand> result = new ArrayList<>();
        for (PlanPriceBandScopeKind oldScope : PlanPriceBandScopeKind.values()) {
            EntitlementScopeKind scope = switch (oldScope) {
                case TEAM -> EntitlementScopeKind.TEAM;
                case ORG -> EntitlementScopeKind.ORG;
            };
            BillingPriceSelector.LegacyPlanSelection selection = selections.get(
                    new BillingPriceSelector.PlanScopeKey(planKey, scope));
            if (selection == null) {
                continue;
            }
            if (!"FREE".equals(planKey) && selection.state() == BillingPriceSelector.LegacyPlanSelectionState.SELECTED) {
                selection.snapshot().bands().stream()
                        .sorted(java.util.Comparator.comparing(BillingPriceSelector.LegacyPlanBandSnapshot::bandNo))
                        .map(band -> priceMapper.toCatalogBand(band, scope.name())).forEach(result::add);
            } else if (selection.oldPrice() != null && ("FREE".equals(planKey)
                    || selection.state() == BillingPriceSelector.LegacyPlanSelectionState.NO_PUBLISHED_PRICE)) {
                BillingPriceSelector.LegacyPlanOldPriceSnapshot oldPrice = selection.oldPrice();
                oldPrice.bands().stream().filter(band -> band.scopeKind() == oldScope)
                        .filter(band -> "FREE".equals(planKey) || Integer.valueOf(0).equals(
                                band.monthlyPriceJpy() == null ? oldPrice.baseMonthlyPriceJpy() : band.monthlyPriceJpy()))
                        .sorted(java.util.Comparator.comparing(BillingPriceSelector.LegacyPlanOldBandSnapshot::bandNo))
                        .map(priceMapper::toOldCatalogBand).forEach(result::add);
            }
        }
        return result;
    }

    static FeatureItem toFeatureItem(FeatureCatalogEntity feature) {
        return FeatureItem.builder()
                .featureKey(feature.getFeatureKey())
                .category(feature.getCategory().name())
                .addonAvailable(Boolean.TRUE.equals(feature.getAddonAvailable()))
                .addonPriceJpy(feature.getAddonPriceJpy())
                .displayNameKey(feature.getDisplayNameKey())
                .descriptionKey(feature.getDescriptionKey())
                .sortOrder(feature.getSortOrder() == null ? 0 : feature.getSortOrder())
                .build();
    }
}
