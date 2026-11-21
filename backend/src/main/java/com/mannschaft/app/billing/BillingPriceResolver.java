package com.mannschaft.app.billing;

import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 契約時点の PLAN 価格と人数帯を解決する。
 * 新価格は適格な ACTIVE の税込額を優先し、未公開時だけ旧最終額0を維持する。
 * literal FREE と ADDON は既存の分類を維持する。
 */
@Service
@RequiredArgsConstructor
public class BillingPriceResolver {

    private final PlanRepository planRepository;
    private final FeatureCatalogRepository featureCatalogRepository;
    private final ScopeMemberCountService scopeMemberCountService;
    private final BillingPriceSelector billingPriceSelector;
    private final LegacyPlanPriceMapper legacyPlanPriceMapper;

    /**
     * 契約の月額（円）を解決する。
     *
     * <p>「プラン不在」と「価格未設定」は意味が異なる（早馬・課金事故対応）。{@code planKey} 自体が
     * マスタに存在しない場合は呼び出し元（{@code BillingContractService}）が別途 {@code PLAN_NOT_FOUND}
     * を検証するため {@code null} を返す。一方、プランは存在するのに月額（base/band とも）が NULL の場合は
     * マスタ整備漏れであり「0 円の無償プラン」ではないため、無償フローへ畳まず
     * {@link EntitlementErrorCode#PLAN_PRICE_NOT_CONFIGURED} を投げて契約自体を拒否する。</p>
     *
     * @return 月額（円）。{@code planKey} がマスタに存在しない場合のみ {@code null}
     * @throws BusinessException プランは存在するが価格が未設定（NULL）の場合
     */
    public Integer resolveMonthlyPriceJpy(
            EntitlementScopeKind scopeKind, Long scopeId, ContractKind contractKind,
            String planKey, String featureKey) {
        if (contractKind == ContractKind.ADDON) {
            return featureCatalogRepository.findById(featureKey)
                    .map(FeatureCatalogEntity::getAddonPriceJpy)
                    .orElse(null);
        }
        // 単一IDでも検索結果から保存済みの綴りを取得し、DB照合規則を維持する。
        PlanEntity plan = planRepository.findAllById(List.of(planKey)).stream().findFirst().orElse(null);
        return plan == null ? null : resolvePlanPrice(plan, scopeKind, scopeId).priceJpy();
    }

    /** 同じ選択結果から契約の価格・人数・帯を確定する。 */
    ResolvedPlanPrice resolvePlanPrice(PlanEntity plan, EntitlementScopeKind scopeKind, Long scopeId) {
        Integer members = scopeKind == EntitlementScopeKind.USER ? null
                : scopeMemberCountService.countActiveMembers(scopeKind, scopeId);
        BillingPriceSelector.PlanScopeKey key = new BillingPriceSelector.PlanScopeKey(plan.getPlanKey(), scopeKind);
        BillingPriceSelector.LegacyPlanSelection selection = billingPriceSelector.selectLegacyPlans(Set.of(key)).get(key);
        if ("FREE".equals(plan.getPlanKey())) {
            return legacyFreePrice(selection == null ? null : selection.oldPrice(), scopeKind, members, false);
        }
        if (selection == null || selection.state() == BillingPriceSelector.LegacyPlanSelectionState.NOT_SELLABLE) {
            throw notConfigured();
        }
        if (selection.state() == BillingPriceSelector.LegacyPlanSelectionState.NO_PUBLISHED_PRICE) {
            return legacyFreePrice(selection.oldPrice(), scopeKind, members, true);
        }
        int target = members == null ? 1 : members;
        List<BillingPriceSelector.LegacyPlanBandSnapshot> bands = selection.snapshot().bands().stream()
                .filter(band -> band.minMembers() != null && target >= band.minMembers()
                        && (band.maxMembers() == null || target <= band.maxMembers()))
                .toList();
        if (bands.size() != 1) {
            throw notConfigured();
        }
        BillingPriceSelector.LegacyPlanBandSnapshot band = bands.getFirst();
        return new ResolvedPlanPrice(legacyPlanPriceMapper.requireContractPriceJpy(band.amountIncludingTax()),
                members, members == null ? null : legacyPlanPriceMapper.requireContractBandNo(band.bandNo()));
    }

    private ResolvedPlanPrice legacyFreePrice(
            BillingPriceSelector.LegacyPlanOldPriceSnapshot oldPrice,
            EntitlementScopeKind scopeKind, Integer members, boolean explicitFreeOnly) {
        if (oldPrice == null) {
            throw notConfigured();
        }
        Integer price = oldPrice.baseMonthlyPriceJpy();
        Short bandNo = null;
        if (members != null) {
            PlanPriceBandScopeKind bandScope = scopeKind == EntitlementScopeKind.TEAM
                    ? PlanPriceBandScopeKind.TEAM : PlanPriceBandScopeKind.ORG;
            for (BillingPriceSelector.LegacyPlanOldBandSnapshot band : oldPrice.bands().stream()
                    .filter(candidate -> candidate.scopeKind() == bandScope)
                    .sorted(java.util.Comparator.comparing(BillingPriceSelector.LegacyPlanOldBandSnapshot::bandNo)).toList()) {
                if (band.minMembers() != null && members >= band.minMembers()
                        && (band.maxMembers() == null || members <= band.maxMembers())) {
                    price = band.monthlyPriceJpy() == null ? price : band.monthlyPriceJpy();
                    bandNo = band.bandNo();
                    break;
                }
            }
        }
        if (price == null || price < 0 || (explicitFreeOnly && price != 0)) {
            throw notConfigured();
        }
        return new ResolvedPlanPrice(price, members, bandNo);
    }

    private BusinessException notConfigured() {
        return new BusinessException(EntitlementErrorCode.PLAN_PRICE_NOT_CONFIGURED);
    }

    record ResolvedPlanPrice(Integer priceJpy, Integer memberCount, Short bandNo) { }
}
