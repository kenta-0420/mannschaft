package com.mannschaft.app.billing;

import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link EntitlementGuard} 単体テスト（試練先行）。
 *
 * <p>対象 AC: AC-09（未充足＋購入可能→402 相当 FEATURE_NOT_ENTITLED／購入不可→403 相当 FEATURE_FORBIDDEN_FOR_SCOPE）／
 * AC-18（不明/無効キーは 403 側）。HTTP ステータス自体は API 層（別部隊）だが、ここでは ErrorCode の選択を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EntitlementGuard 単体テスト（402/403 の使い分け）")
class EntitlementGuardTest {

    @Mock
    private EntitlementQueryService entitlementQueryService;
    @Mock
    private FeatureCatalogRepository featureCatalogRepository;
    @Mock
    private PlanFeatureRepository planFeatureRepository;

    @InjectMocks
    private EntitlementGuard guard;

    private FeatureCatalogEntity feature(String key, boolean enabled, boolean addonAvailable) {
        return feature(key, enabled, addonAvailable, null);
    }

    /** F20.1 402 details 追補: addonPriceJpy も渡せるよう拡張したヘルパ（既存 3 引数呼び出しは維持）。 */
    private FeatureCatalogEntity feature(String key, boolean enabled, boolean addonAvailable, Integer addonPriceJpy) {
        return FeatureCatalogEntity.builder()
                .featureKey(key)
                .category(FeatureCategory.INTERNAL)
                .addonAvailable(addonAvailable)
                .addonPriceJpy(addonPriceJpy)
                .freeForNonprofit(false)
                .displayNameKey("k.name")
                .descriptionKey("k.desc")
                .sortOrder(0)
                .enabled(enabled)
                .build();
    }

    @Test
    @DisplayName("充足時は例外を投げない")
    void passesWhenEntitled() {
        given(entitlementQueryService.isEntitled(EntitlementScopeKind.TEAM, 10L, FeatureKeys.ADS_HIDE))
                .willReturn(true);
        assertThatCode(() -> guard.require(EntitlementScopeKind.TEAM, 10L, FeatureKeys.ADS_HIDE))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("AC-1: 未充足＋アドオン購入可能 → FeatureNotEntitledException（402 相当）・details.addonAvailable=true")
    void notEntitledButPurchasableThrows402Code() {
        String key = FeatureKeys.ADS_HIDE;
        given(entitlementQueryService.isEntitled(EntitlementScopeKind.TEAM, 10L, key)).willReturn(false);
        given(featureCatalogRepository.findById(key)).willReturn(Optional.of(feature(key, true, true)));
        given(planFeatureRepository.findPurchasablePlanKeysContaining(key)).willReturn(List.of());

        assertThatThrownBy(() -> guard.require(EntitlementScopeKind.TEAM, 10L, key))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(EntitlementErrorCode.FEATURE_NOT_ENTITLED);

        assertThatThrownBy(() -> guard.require(EntitlementScopeKind.TEAM, 10L, key))
                .isInstanceOf(FeatureNotEntitledException.class)
                .satisfies(e -> {
                    EntitlementNotEntitledDetails details = ((FeatureNotEntitledException) e).getDetails();
                    assertThat(details.getFeatureKey()).isEqualTo(key);
                    assertThat(details.isAddonAvailable()).isTrue();
                });
    }

    @Test
    @DisplayName("AC-09: 未充足＋購入手段なし（addon 不可・プラン未掲載）→ FEATURE_FORBIDDEN_FOR_SCOPE（403 相当）")
    void notEntitledNotPurchasableThrows403Code() {
        String key = FeatureKeys.ADS_HIDE;
        given(entitlementQueryService.isEntitled(EntitlementScopeKind.TEAM, 10L, key)).willReturn(false);
        given(featureCatalogRepository.findById(key)).willReturn(Optional.of(feature(key, true, false)));
        given(planFeatureRepository.existsPurchasablePlanContaining(key)).willReturn(false);

        assertThatThrownBy(() -> guard.require(EntitlementScopeKind.TEAM, 10L, key))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(EntitlementErrorCode.FEATURE_FORBIDDEN_FOR_SCOPE);
    }

    @Test
    @DisplayName("AC-2: 未充足だが非 FREE プランに掲載されていれば購入可能 → 402 相当・details.addonAvailable=false・plansContainingにプランキー")
    void notEntitledButOnPaidPlanThrows402Code() {
        String key = FeatureKeys.TEMPLATE_PREMIUM_MODULES;
        given(entitlementQueryService.isEntitled(EntitlementScopeKind.TEAM, 10L, key)).willReturn(false);
        given(featureCatalogRepository.findById(key)).willReturn(Optional.of(feature(key, true, false)));
        given(planFeatureRepository.existsPurchasablePlanContaining(key)).willReturn(true);
        given(planFeatureRepository.findPurchasablePlanKeysContaining(key)).willReturn(List.of("FULL"));

        assertThatThrownBy(() -> guard.require(EntitlementScopeKind.TEAM, 10L, key))
                .isInstanceOf(FeatureNotEntitledException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(EntitlementErrorCode.FEATURE_NOT_ENTITLED);

        assertThatThrownBy(() -> guard.require(EntitlementScopeKind.TEAM, 10L, key))
                .isInstanceOf(FeatureNotEntitledException.class)
                .satisfies(e -> {
                    EntitlementNotEntitledDetails details = ((FeatureNotEntitledException) e).getDetails();
                    assertThat(details.isAddonAvailable()).isFalse();
                    assertThat(details.getPlansContaining()).containsExactly("FULL");
                });
    }

    @Test
    @DisplayName("AC-18: 不明/無効 feature_key は 403 側（FEATURE_FORBIDDEN_FOR_SCOPE・fail-safe）")
    void unknownFeatureIsForbidden() {
        String key = "does.not.exist";
        given(entitlementQueryService.isEntitled(EntitlementScopeKind.TEAM, 10L, key)).willReturn(false);
        given(featureCatalogRepository.findById(key)).willReturn(Optional.empty());

        assertThatThrownBy(() -> guard.require(EntitlementScopeKind.TEAM, 10L, key))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(EntitlementErrorCode.FEATURE_FORBIDDEN_FOR_SCOPE);
    }

    @Test
    @DisplayName("AC-3: details.scopeKind/scopeId が require 引数どおり（USER/TEAM/ORG）")
    void detailsScopeMatchesRequireArguments() {
        String key = FeatureKeys.ADS_HIDE;
        given(entitlementQueryService.isEntitled(EntitlementScopeKind.ORG, 77L, key)).willReturn(false);
        given(featureCatalogRepository.findById(key)).willReturn(Optional.of(feature(key, true, true)));
        given(planFeatureRepository.findPurchasablePlanKeysContaining(key)).willReturn(List.of());

        assertThatThrownBy(() -> guard.require(EntitlementScopeKind.ORG, 77L, key))
                .isInstanceOf(FeatureNotEntitledException.class)
                .satisfies(e -> {
                    EntitlementNotEntitledDetails details = ((FeatureNotEntitledException) e).getDetails();
                    assertThat(details.getScopeKind()).isEqualTo("ORG");
                    assertThat(details.getScopeId()).isEqualTo(77L);
                });
    }

    @Test
    @DisplayName("AC-8: addonPriceJpy=null の機能 → details.addonPriceJpy=null")
    void detailsAddonPriceJpyNullWhenUndetermined() {
        String key = FeatureKeys.ADS_HIDE;
        given(entitlementQueryService.isEntitled(EntitlementScopeKind.TEAM, 10L, key)).willReturn(false);
        given(featureCatalogRepository.findById(key)).willReturn(Optional.of(feature(key, true, true, null)));
        given(planFeatureRepository.findPurchasablePlanKeysContaining(key)).willReturn(List.of());

        assertThatThrownBy(() -> guard.require(EntitlementScopeKind.TEAM, 10L, key))
                .isInstanceOf(FeatureNotEntitledException.class)
                .satisfies(e -> assertThat(((FeatureNotEntitledException) e).getDetails().getAddonPriceJpy())
                        .isNull());
    }

    @Test
    @DisplayName("AC-9: plansContaining 0件 → 空配列（null 禁止）")
    void detailsPlansContainingEmptyNotNull() {
        String key = FeatureKeys.ADS_HIDE;
        given(entitlementQueryService.isEntitled(EntitlementScopeKind.TEAM, 10L, key)).willReturn(false);
        given(featureCatalogRepository.findById(key)).willReturn(Optional.of(feature(key, true, true)));
        given(planFeatureRepository.findPurchasablePlanKeysContaining(key)).willReturn(List.of());

        assertThatThrownBy(() -> guard.require(EntitlementScopeKind.TEAM, 10L, key))
                .isInstanceOf(FeatureNotEntitledException.class)
                .satisfies(e -> assertThat(((FeatureNotEntitledException) e).getDetails().getPlansContaining())
                        .isNotNull()
                        .isEmpty());
    }

    @Test
    @DisplayName("AC-11: addonPriceJpy=0 → details.addonPriceJpy=0 が載る（null と区別される）")
    void detailsAddonPriceJpyZeroIsPreserved() {
        String key = FeatureKeys.ADS_HIDE;
        given(entitlementQueryService.isEntitled(EntitlementScopeKind.TEAM, 10L, key)).willReturn(false);
        given(featureCatalogRepository.findById(key)).willReturn(Optional.of(feature(key, true, true, 0)));
        given(planFeatureRepository.findPurchasablePlanKeysContaining(key)).willReturn(List.of());

        assertThatThrownBy(() -> guard.require(EntitlementScopeKind.TEAM, 10L, key))
                .isInstanceOf(FeatureNotEntitledException.class)
                .satisfies(e -> assertThat(((FeatureNotEntitledException) e).getDetails().getAddonPriceJpy())
                        .isEqualTo(0));
    }

    @Test
    @DisplayName("AC-16(guardレベル): 403 時は details を持たない素の BusinessException（FeatureNotEntitledException ではない）")
    void forbiddenPathHasNoDetailsType() {
        String key = FeatureKeys.ADS_HIDE;
        given(entitlementQueryService.isEntitled(EntitlementScopeKind.TEAM, 10L, key)).willReturn(false);
        given(featureCatalogRepository.findById(key)).willReturn(Optional.of(feature(key, true, false)));
        given(planFeatureRepository.existsPurchasablePlanContaining(key)).willReturn(false);

        assertThatThrownBy(() -> guard.require(EntitlementScopeKind.TEAM, 10L, key))
                .isNotInstanceOf(FeatureNotEntitledException.class)
                .isExactlyInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("AC-21: 402 経路で featureCatalogRepository.findById が1回だけ呼ばれる（二重フェッチ禁止）")
    void findByIdCalledOnlyOnceOnNotEntitledPath() {
        String key = FeatureKeys.ADS_HIDE;
        given(entitlementQueryService.isEntitled(EntitlementScopeKind.TEAM, 10L, key)).willReturn(false);
        given(featureCatalogRepository.findById(key)).willReturn(Optional.of(feature(key, true, true)));
        given(planFeatureRepository.findPurchasablePlanKeysContaining(key)).willReturn(List.of());

        assertThatThrownBy(() -> guard.require(EntitlementScopeKind.TEAM, 10L, key))
                .isInstanceOf(FeatureNotEntitledException.class);

        verify(featureCatalogRepository, times(1)).findById(key);
    }
}
