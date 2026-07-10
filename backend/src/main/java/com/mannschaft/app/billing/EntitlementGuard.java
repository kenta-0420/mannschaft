package com.mannschaft.app.billing;

import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

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
        if (isPurchasable(featureKey)) {
            throw new BusinessException(EntitlementErrorCode.FEATURE_NOT_ENTITLED);      // → 402（AC-09）
        }
        throw new BusinessException(EntitlementErrorCode.FEATURE_FORBIDDEN_FOR_SCOPE);   // → 403（AC-09/18）
    }

    /**
     * 機能が購入可能か（enabled かつ [addon_available または enabled な非 FREE プランに掲載]）。
     * 不明/無効キーは購入不可＝403 側（fail-safe・AC-18）。
     */
    private boolean isPurchasable(String featureKey) {
        FeatureCatalogEntity feature = featureCatalogRepository.findById(featureKey).orElse(null);
        if (feature == null || !Boolean.TRUE.equals(feature.getEnabled())) {
            return false;
        }
        return Boolean.TRUE.equals(feature.getAddonAvailable())
                || planFeatureRepository.existsPurchasablePlanContaining(featureKey);
    }
}
