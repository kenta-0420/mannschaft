package com.mannschaft.app.billing;

import com.mannschaft.app.billing.api.dto.EntitlementNotEntitledDetails;
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
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

/**
 * {@link EntitlementGuard} 単体テスト（試練先行）。
 *
 * <p>対象 AC: AC-09（未充足＋購入可能→402 相当 FEATURE_NOT_ENTITLED／購入不可→403 相当 FEATURE_FORBIDDEN_FOR_SCOPE）／
 * AC-18（不明/無効キーは 403 側）。HTTP ステータス自体は API 層（別部隊）だが、ここでは ErrorCode の選択を検証する。</p>
 *
 * <p>設計書 02 §1.2 取りこぼし根治: 402 には購入導線 details
 * （{@link EntitlementNotEntitledDetails}: featureKey / addonAvailable / addonPriceJpy / plansContaining）が
 * 添付され、403 には添付されないことも本テストで契約検証する。</p>
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
        return FeatureCatalogEntity.builder()
                .featureKey(key)
                .category(FeatureCategory.INTERNAL)
                .addonAvailable(addonAvailable)
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
    @DisplayName("AC-09: 未充足＋アドオン購入可能 → FEATURE_NOT_ENTITLED（402 相当）＋購入導線 details 添付")
    void notEntitledButPurchasableThrows402Code() {
        String key = FeatureKeys.ADS_HIDE;
        given(entitlementQueryService.isEntitled(EntitlementScopeKind.TEAM, 10L, key)).willReturn(false);
        given(featureCatalogRepository.findById(key)).willReturn(Optional.of(feature(key, true, true)));
        given(planFeatureRepository.findPurchasablePlanKeysContaining(key)).willReturn(List.of("FULL"));

        BusinessException ex = catchThrowableOfType(
                () -> guard.require(EntitlementScopeKind.TEAM, 10L, key), BusinessException.class);

        assertThat(ex.getErrorCode()).isEqualTo(EntitlementErrorCode.FEATURE_NOT_ENTITLED);
        // 設計書 02 §1.2: 402 の details に購入導線を含める。
        assertThat(ex.getDetails()).isInstanceOf(EntitlementNotEntitledDetails.class);
        EntitlementNotEntitledDetails details = (EntitlementNotEntitledDetails) ex.getDetails();
        assertThat(details.featureKey()).isEqualTo(key);
        assertThat(details.addonAvailable()).isTrue();
        assertThat(details.plansContaining()).containsExactly("FULL");
    }

    @Test
    @DisplayName("02 §1.2: 402 details の addonPriceJpy は addon 可のとき feature_catalog の値（未定は null）")
    void details402CarriesAddonPrice() {
        String key = FeatureKeys.ADS_HIDE;
        FeatureCatalogEntity priced = feature(key, true, true);
        priced.setAddonPriceJpy(300);
        given(entitlementQueryService.isEntitled(EntitlementScopeKind.USER, 9L, key)).willReturn(false);
        given(featureCatalogRepository.findById(key)).willReturn(Optional.of(priced));
        given(planFeatureRepository.findPurchasablePlanKeysContaining(key)).willReturn(List.of());

        BusinessException ex = catchThrowableOfType(
                () -> guard.require(EntitlementScopeKind.USER, 9L, key), BusinessException.class);

        EntitlementNotEntitledDetails details = (EntitlementNotEntitledDetails) ex.getDetails();
        assertThat(details.addonPriceJpy()).isEqualTo(300);
        assertThat(details.plansContaining()).isEmpty();
    }

    @Test
    @DisplayName("AC-09: 未充足＋購入手段なし（addon 不可・プラン未掲載）→ FEATURE_FORBIDDEN_FOR_SCOPE（403 相当）・details なし")
    void notEntitledNotPurchasableThrows403Code() {
        String key = FeatureKeys.ADS_HIDE;
        given(entitlementQueryService.isEntitled(EntitlementScopeKind.TEAM, 10L, key)).willReturn(false);
        given(featureCatalogRepository.findById(key)).willReturn(Optional.of(feature(key, true, false)));
        given(planFeatureRepository.findPurchasablePlanKeysContaining(key)).willReturn(List.of());

        BusinessException ex = catchThrowableOfType(
                () -> guard.require(EntitlementScopeKind.TEAM, 10L, key), BusinessException.class);

        assertThat(ex.getErrorCode()).isEqualTo(EntitlementErrorCode.FEATURE_FORBIDDEN_FOR_SCOPE);
        // 02 §1.2: 購入不可（403）には購入導線を出さない。
        assertThat(ex.getDetails()).isNull();
    }

    @Test
    @DisplayName("AC-09: 未充足だが非 FREE プランに掲載されていれば購入可能 → 402 相当＋plansContaining に掲載プラン")
    void notEntitledButOnPaidPlanThrows402Code() {
        String key = FeatureKeys.TEMPLATE_PREMIUM_MODULES;
        given(entitlementQueryService.isEntitled(EntitlementScopeKind.TEAM, 10L, key)).willReturn(false);
        given(featureCatalogRepository.findById(key)).willReturn(Optional.of(feature(key, true, false)));
        given(planFeatureRepository.findPurchasablePlanKeysContaining(key)).willReturn(List.of("BASIC", "FULL"));

        BusinessException ex = catchThrowableOfType(
                () -> guard.require(EntitlementScopeKind.TEAM, 10L, key), BusinessException.class);

        assertThat(ex.getErrorCode()).isEqualTo(EntitlementErrorCode.FEATURE_NOT_ENTITLED);
        EntitlementNotEntitledDetails details = (EntitlementNotEntitledDetails) ex.getDetails();
        assertThat(details.featureKey()).isEqualTo(key);
        // addon 不可のため addonPriceJpy は null（purchasable の根拠はプラン掲載）。
        assertThat(details.addonAvailable()).isFalse();
        assertThat(details.addonPriceJpy()).isNull();
        assertThat(details.plansContaining()).containsExactly("BASIC", "FULL");
    }

    @Test
    @DisplayName("AC-18: 不明/無効 feature_key は 403 側（FEATURE_FORBIDDEN_FOR_SCOPE・fail-safe）・details なし")
    void unknownFeatureIsForbidden() {
        String key = "does.not.exist";
        given(entitlementQueryService.isEntitled(EntitlementScopeKind.TEAM, 10L, key)).willReturn(false);
        given(featureCatalogRepository.findById(key)).willReturn(Optional.empty());

        BusinessException ex = catchThrowableOfType(
                () -> guard.require(EntitlementScopeKind.TEAM, 10L, key), BusinessException.class);

        assertThat(ex.getErrorCode()).isEqualTo(EntitlementErrorCode.FEATURE_FORBIDDEN_FOR_SCOPE);
        assertThat(ex.getDetails()).isNull();
    }
}
