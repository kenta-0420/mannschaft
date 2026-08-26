package com.mannschaft.app.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * F20.1 実決済（D-4）: {@link BillingPriceResolver} 単体テスト（試練）。
 *
 * <p>AC-40 の中核: <b>価格 NULL＝無償フロー／非 NULL＝決済フロー</b>の分岐判定材料を正しく解決する。
 * PLAN・USER=base／PLAN・TEAM/ORG=バンド優先→base フォールバック／ADDON=addon_price_jpy。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BillingPriceResolver 単体テスト（価格解決 D-4）")
class BillingPriceResolverTest {

    @Mock private PlanRepository planRepository;
    @Mock private FeatureCatalogRepository featureCatalogRepository;
    @Mock private PlanPriceBandRepository planPriceBandRepository;
    @Mock private ScopeMemberCountService scopeMemberCountService;

    @InjectMocks private BillingPriceResolver resolver;

    private PlanEntity plan(String key, Integer base) {
        return PlanEntity.builder().planKey(key).displayNameKey("k").descriptionKey("k")
                .sortOrder(1).enabled(true).baseMonthlyPriceJpy(base).build();
    }

    private FeatureCatalogEntity feature(String key, Integer addonPrice) {
        return FeatureCatalogEntity.builder().featureKey(key).category(FeatureCategory.INTERNAL)
                .addonAvailable(true).freeForNonprofit(false)
                .displayNameKey("k").descriptionKey("k").sortOrder(0).enabled(true)
                .addonPriceJpy(addonPrice).build();
    }

    private PlanPriceBandEntity band(String plan, PlanPriceBandScopeKind scope, short no,
                                     int min, Integer max, Integer price) {
        return PlanPriceBandEntity.builder().planKey(plan).scopeKind(scope).bandNo(no)
                .minMembers(min).maxMembers(max).monthlyPriceJpy(price).build();
    }

    @Test
    @DisplayName("AC-40 PLAN・USER: base_monthly_price_jpy が NULL なら無償（null）")
    void plan_user_baseNull_returnsNull() {
        given(planRepository.findById("FREE")).willReturn(Optional.of(plan("FREE", null)));
        assertThat(resolver.resolveMonthlyPriceJpy(
                EntitlementScopeKind.USER, 9L, ContractKind.PLAN, "FREE", null)).isNull();
    }

    @Test
    @DisplayName("AC-40 PLAN・USER: base が設定済みならその額（決済フロー）")
    void plan_user_basePriced() {
        given(planRepository.findById("FULL")).willReturn(Optional.of(plan("FULL", 2000)));
        assertThat(resolver.resolveMonthlyPriceJpy(
                EntitlementScopeKind.USER, 9L, ContractKind.PLAN, "FULL", null)).isEqualTo(2000);
    }

    @Test
    @DisplayName("AC-40 PLAN・TEAM: 人数バンドの月額を優先する")
    void plan_team_bandPriceWins() {
        given(planRepository.findById("FULL")).willReturn(Optional.of(plan("FULL", 2000)));
        given(scopeMemberCountService.countActiveMembers(EntitlementScopeKind.TEAM, 5L)).willReturn(30);
        given(planPriceBandRepository.findByPlanKeyAndScopeKindOrderByBandNoAsc("FULL", PlanPriceBandScopeKind.TEAM))
                .willReturn(List.of(
                        band("FULL", PlanPriceBandScopeKind.TEAM, (short) 1, 1, 20, 1500),
                        band("FULL", PlanPriceBandScopeKind.TEAM, (short) 2, 21, null, 3000)));
        assertThat(resolver.resolveMonthlyPriceJpy(
                EntitlementScopeKind.TEAM, 5L, ContractKind.PLAN, "FULL", null)).isEqualTo(3000);
    }

    @Test
    @DisplayName("AC-40 PLAN・ORG: バンド価格が NULL なら base へフォールバック")
    void plan_org_bandNullFallsBackToBase() {
        given(planRepository.findById("FULL")).willReturn(Optional.of(plan("FULL", 2000)));
        given(scopeMemberCountService.countActiveMembers(EntitlementScopeKind.ORG, 7L)).willReturn(10);
        given(planPriceBandRepository.findByPlanKeyAndScopeKindOrderByBandNoAsc("FULL", PlanPriceBandScopeKind.ORG))
                .willReturn(List.of(band("FULL", PlanPriceBandScopeKind.ORG, (short) 1, 1, null, null)));
        assertThat(resolver.resolveMonthlyPriceJpy(
                EntitlementScopeKind.ORG, 7L, ContractKind.PLAN, "FULL", null)).isEqualTo(2000);
    }

    @Test
    @DisplayName("AC-40 ADDON: feature_catalog.addon_price_jpy を返す（NULL なら無償）")
    void addon_price() {
        given(featureCatalogRepository.findById("ads.hide")).willReturn(Optional.of(feature("ads.hide", 300)));
        assertThat(resolver.resolveMonthlyPriceJpy(
                EntitlementScopeKind.USER, 9L, ContractKind.ADDON, null, "ads.hide")).isEqualTo(300);

        given(featureCatalogRepository.findById("free.feature")).willReturn(Optional.of(feature("free.feature", null)));
        assertThat(resolver.resolveMonthlyPriceJpy(
                EntitlementScopeKind.USER, 9L, ContractKind.ADDON, null, "free.feature")).isNull();
    }
}
