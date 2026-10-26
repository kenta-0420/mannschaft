package com.mannschaft.app.billing;

import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * F20.1: BE ゲート（設計書 02 §1.2・03 §4）。
 *
 * <p>未充足なら 402/403 の {@link BusinessException} を投げる。<b>public 入口（Controller→Service の入口）で
 * 呼ぶ</b>こと。共有内部メソッドに置くとバッチ・イベント処理が巻き添えで 402 になる
 * （memory {@code feedback_authz_gate_on_public_entry_not_shared_method}）。
 * FE だけのペイウォールは禁止（BE ゲート必須・memory {@code project_paywall_be_body_gate_required}）。</p>
 *
 * <p><b>402/403 の使い分け</b>（設計書 03 §3）:</p>
 * <ul>
 *   <li>権利なし・<b>購入手段あり</b>（addon_available=true または enabled な非 FREE プランに掲載）
 *       → {@code FEATURE_NOT_ENTITLED}（HTTP 402・購入導線を出す）。</li>
 *   <li>権利なし・購入手段なし（カタログ enabled=false・どのプランにも未掲載・スコープ不適合）
 *       → {@code FEATURE_FORBIDDEN_FOR_SCOPE}（HTTP 403・AC-18 の fail-safe を含む）。</li>
 * </ul>
 *
 * <p>F12.2 フィーチャーフラグ（kill switch）は見ない（責務分離・README §4.4。各機能入口で別途評価）。</p>
 */
@Component
@RequiredArgsConstructor
public class EntitlementGuard {

    private final EntitlementQueryService entitlementQueryService;
    private final FeatureCatalogRepository featureCatalogRepository;
    private final PlanFeatureRepository planFeatureRepository;

    /**
     * 権利を要求する。未充足なら 402（購入可能）/403（購入不可）の {@link BusinessException} を投げる。
     *
     * @param scopeKind  USER / TEAM / ORG
     * @param scopeId    users.id / teams.id / organizations.id
     * @param featureKey feature_catalog.feature_key
     * @throws BusinessException {@code FEATURE_NOT_ENTITLED}（402）または {@code FEATURE_FORBIDDEN_FOR_SCOPE}（403）
     */
    public void require(EntitlementScopeKind scopeKind, Long scopeId, String featureKey) {
        if (entitlementQueryService.isEntitled(scopeKind, scopeId, featureKey)) {
            return;
        }
        // feature は 1 回だけ引く（AC-21・購入可否判定と 402 details 生成で共有）。
        FeatureCatalogEntity feature = featureCatalogRepository.findById(featureKey).orElse(null);
        if (isPurchasable(feature)) {
            throw new FeatureNotEntitledException(buildDetails(feature, featureKey, scopeKind, scopeId)); // → 402（AC-09）
        }
        throw new BusinessException(EntitlementErrorCode.FEATURE_FORBIDDEN_FOR_SCOPE);   // → 403（AC-09/18）
    }

    /**
     * 機能が購入可能か（enabled かつ [addon_available または enabled な非 FREE プランに掲載]）。
     * 不明/無効キーは購入不可＝403 側（fail-safe・AC-18）。
     */
    private boolean isPurchasable(FeatureCatalogEntity feature) {
        if (feature == null || !Boolean.TRUE.equals(feature.getEnabled())) {
            return false;
        }
        return Boolean.TRUE.equals(feature.getAddonAvailable())
                || planFeatureRepository.existsPurchasablePlanContaining(feature.getFeatureKey());
    }

    /**
     * 402 応答の details（購入導線情報）を組み立てる（設計書・AC-1/2/3/8/9/11・AC-20/21）。
     */
    private EntitlementNotEntitledDetails buildDetails(FeatureCatalogEntity feature, String featureKey,
                                                        EntitlementScopeKind scopeKind, Long scopeId) {
        List<String> plansContaining = planFeatureRepository.findPurchasablePlanKeysContaining(featureKey);
        return EntitlementNotEntitledDetails.builder()
                .featureKey(featureKey)
                .addonAvailable(Boolean.TRUE.equals(feature.getAddonAvailable()))
                .addonPriceJpy(feature.getAddonPriceJpy())
                .plansContaining(plansContaining != null ? plansContaining : List.of())
                .scopeKind(scopeKind.name())
                .scopeId(scopeId)
                .build();
    }
}
