package com.mannschaft.app.billing;

import com.mannschaft.app.common.BusinessException;
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
        // PLAN
        PlanEntity plan = planRepository.findById(planKey).orElse(null);
        if (plan == null) {
            // プラン自体が不在 → 呼び出し元の PLAN_NOT_FOUND 検証に委ねる（無償扱いにしない）。
            return null;
        }
        Integer base = plan.getBaseMonthlyPriceJpy();
        if (scopeKind == EntitlementScopeKind.USER) {
            return requireConfigured(base);
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
                return requireConfigured(band.getMonthlyPriceJpy() != null ? band.getMonthlyPriceJpy() : base);
            }
        }
        return requireConfigured(base);
    }

    /**
     * 「0 円と明示された無償プラン」（base=0）はそのまま通し、「価格未設定（NULL）」のみ拒否する。
     * プランがマスタに存在すると確定した後の最終価格にのみ適用する（プラン不在時は呼ばない）。
     */
    private Integer requireConfigured(Integer priceJpy) {
        if (priceJpy == null) {
            throw new BusinessException(EntitlementErrorCode.PLAN_PRICE_NOT_CONFIGURED);
        }
        return priceJpy;
    }
}
