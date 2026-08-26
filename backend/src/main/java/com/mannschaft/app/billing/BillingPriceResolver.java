package com.mannschaft.app.billing;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * F20.1 実決済（D-4・2026-07-10 御裁可）: 契約の月額（円）をマスタから解決する。
 *
 * <p><b>NULL＝無償ワンクリック契約（既存 P1 フロー）／非 NULL＝Checkout 決済フロー</b>の分岐に用いる。
 * 価格はマスタデータ（{@code plans} / {@code plan_price_bands} / {@code feature_catalog}）。既存無償契約に
 * 遡及しない（D-4）ため、価格は「契約作成時点」に解決してスナップショット（{@code price_jpy_snapshot}）へ焼き付ける。</p>
 *
 * <ul>
 *   <li>PLAN・USER: {@code plans.base_monthly_price_jpy}。</li>
 *   <li>PLAN・TEAM/ORG: 人数バンド（{@code plan_price_bands}）の {@code monthly_price_jpy} を優先。バンド未定義/価格 NULL は base。</li>
 *   <li>ADDON: {@code feature_catalog.addon_price_jpy}。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class BillingPriceResolver {

    private final PlanRepository planRepository;
    private final FeatureCatalogRepository featureCatalogRepository;
    private final PlanPriceBandRepository planPriceBandRepository;
    private final ScopeMemberCountService scopeMemberCountService;

    /**
     * 契約の月額（円）を解決する。マスタ不整合/未定は {@code null}（無償）を返す（fail-safe＝無償に倒す）。
     *
     * @return 月額（円）。NULL のとき無償フロー、非 NULL のとき決済フロー
     */
    public Integer resolveMonthlyPriceJpy(
            EntitlementScopeKind scopeKind, Long scopeId, ContractKind contractKind,
            String planKey, String featureKey) {
        if (contractKind == ContractKind.ADDON) {
            return featureCatalogRepository.findById(featureKey)
                    .map(FeatureCatalogEntity::getAddonPriceJpy)
                    .orElse(null);
        }
        // PLAN
        PlanEntity plan = planRepository.findById(planKey).orElse(null);
        if (plan == null) {
            return null;
        }
        Integer base = plan.getBaseMonthlyPriceJpy();
        if (scopeKind == EntitlementScopeKind.USER) {
            return base;
        }
        // TEAM / ORG: バンド価格を優先（バンド未定義/価格 NULL は base へフォールバック）。
        PlanPriceBandScopeKind bandScope = scopeKind == EntitlementScopeKind.TEAM
                ? PlanPriceBandScopeKind.TEAM : PlanPriceBandScopeKind.ORG;
        int memberCount = scopeMemberCountService.countActiveMembers(scopeKind, scopeId);
        for (PlanPriceBandEntity band
                : planPriceBandRepository.findByPlanKeyAndScopeKindOrderByBandNoAsc(planKey, bandScope)) {
            boolean lowerOk = band.getMinMembers() != null && memberCount >= band.getMinMembers();
            boolean upperOk = band.getMaxMembers() == null || memberCount <= band.getMaxMembers();
            if (lowerOk && upperOk) {
                return band.getMonthlyPriceJpy() != null ? band.getMonthlyPriceJpy() : base;
            }
        }
        return base;
    }
}
