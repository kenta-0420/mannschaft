package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.FeatureCatalogEntity;
import com.mannschaft.app.billing.FeatureCatalogRepository;
import com.mannschaft.app.billing.FeatureKeys;
import com.mannschaft.app.billing.PlanEntity;
import com.mannschaft.app.billing.PlanFeatureEntity;
import com.mannschaft.app.billing.PlanFeatureRepository;
import com.mannschaft.app.billing.PlanPriceBandEntity;
import com.mannschaft.app.billing.PlanPriceBandRepository;
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
    private final PlanPriceBandRepository planPriceBandRepository;
    private final FeatureCatalogRepository featureCatalogRepository;

    /** プランカタログを組み立てて返す（sort_order 昇順・enabled のみ）。 */
    public PlanCatalogResponse getCatalog() {
        List<PlanItem> items = new ArrayList<>();
        for (PlanEntity plan : planRepository.findByEnabledTrueOrderBySortOrderAsc()) {
            items.add(PlanItem.builder()
                    .planKey(plan.getPlanKey())
                    .displayNameKey(plan.getDisplayNameKey())
                    .descriptionKey(plan.getDescriptionKey())
                    .baseMonthlyPriceJpy(plan.getBaseMonthlyPriceJpy())
                    .features(featuresOfPlan(plan.getPlanKey()))
                    .priceBands(priceBandsOfPlan(plan.getPlanKey()))
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

    private List<PriceBand> priceBandsOfPlan(String planKey) {
        List<PriceBand> bands = new ArrayList<>();
        for (PlanPriceBandScopeKind scope : PlanPriceBandScopeKind.values()) {
            for (PlanPriceBandEntity band
                    : planPriceBandRepository.findByPlanKeyAndScopeKindOrderByBandNoAsc(planKey, scope)) {
                bands.add(PriceBand.builder()
                        .scopeKind(band.getScopeKind().name())
                        .bandNo(band.getBandNo())
                        .minMembers(band.getMinMembers())
                        .maxMembers(band.getMaxMembers())
                        .monthlyPriceJpy(band.getMonthlyPriceJpy())
                        .build());
            }
        }
        return bands;
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
