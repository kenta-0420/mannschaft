package com.mannschaft.app.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link EntitlementQueryService} 単体テスト（試練先行）。
 *
 * <p>対象 AC: AC-01（FULL 対象機能で true）／AC-04（アドオンで X のみ true）／AC-05（FREE 掲載は契約ゼロで true）／
 * AC-18（不明/無効 feature_key は fail-safe で false・existsActiveGrant を呼ばない）／
 * AC-23（entitledFeatureKeys 集合＝isEntitled=true 集合）。</p>
 *
 * <p>半開区間の境界（AC-06/07/08）は {@link EntitlementEntity#isActiveAt} を
 * {@link EntitlementEntityActiveAtTest} で純粋述語として検証する（DB クエリと二重化）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EntitlementQueryService 単体テスト（権利判定の正準実装）")
class EntitlementQueryServiceTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-07-10T00:00:00Z"), ZoneOffset.UTC);

    @Mock
    private FeatureCatalogRepository featureCatalogRepository;
    @Mock
    private PlanFeatureRepository planFeatureRepository;
    @Mock
    private EntitlementRepository entitlementRepository;
    @Mock
    private ScopeClassificationService scopeClassificationService;

    private EntitlementQueryService service;

    private void initService() {
        service = new EntitlementQueryService(
                featureCatalogRepository, planFeatureRepository, entitlementRepository,
                scopeClassificationService, FIXED_CLOCK);
    }

    private FeatureCatalogEntity feature(String key, boolean enabled, boolean freeForNonprofit) {
        FeatureCatalogEntity f = FeatureCatalogEntity.builder()
                .featureKey(key)
                .category(FeatureCategory.INTERNAL)
                .addonAvailable(Boolean.TRUE)
                .freeForNonprofit(freeForNonprofit)
                .displayNameKey("k.name")
                .descriptionKey("k.desc")
                .sortOrder(0)
                .enabled(enabled)
                .build();
        return f;
    }

    @Test
    @DisplayName("AC-01: FULL 対象機能を契約済みチームは isEntitled=true")
    void ac01_entitledTeamReturnsTrue() {
        initService();
        String key = FeatureKeys.ADS_HIDE;
        given(featureCatalogRepository.findById(key)).willReturn(Optional.of(feature(key, true, false)));
        given(planFeatureRepository.existsByPlanKeyAndFeatureKey(FeatureKeys.PLAN_FREE, key)).willReturn(false);
        given(entitlementRepository.existsActiveGrant(eq(EntitlementScopeKind.TEAM), eq(10L), eq(key), any()))
                .willReturn(true);

        assertThat(service.isEntitled(EntitlementScopeKind.TEAM, 10L, key)).isTrue();
    }

    @Test
    @DisplayName("AC-04: アドオン契約は対象 X のみ true・プラン外 Y は false")
    void ac04_addonScopedToFeature() {
        initService();
        String x = FeatureKeys.ADS_HIDE;
        String y = FeatureKeys.TEMPLATE_PREMIUM_MODULES;
        given(featureCatalogRepository.findById(x)).willReturn(Optional.of(feature(x, true, false)));
        given(featureCatalogRepository.findById(y)).willReturn(Optional.of(feature(y, true, false)));
        given(planFeatureRepository.existsByPlanKeyAndFeatureKey(eq(FeatureKeys.PLAN_FREE), any())).willReturn(false);
        given(entitlementRepository.existsActiveGrant(eq(EntitlementScopeKind.TEAM), eq(10L), eq(x), any()))
                .willReturn(true);
        given(entitlementRepository.existsActiveGrant(eq(EntitlementScopeKind.TEAM), eq(10L), eq(y), any()))
                .willReturn(false);

        assertThat(service.isEntitled(EntitlementScopeKind.TEAM, 10L, x)).isTrue();
        assertThat(service.isEntitled(EntitlementScopeKind.TEAM, 10L, y)).isFalse();
    }

    @Test
    @DisplayName("AC-02: チーム T1 の契約は同一 featureKey の別チーム T2 に漏れない（scopeId 隔離）")
    void ac02_teamScopeIsolation() {
        initService();
        String key = FeatureKeys.ADS_HIDE;
        given(featureCatalogRepository.findById(key)).willReturn(Optional.of(feature(key, true, false)));
        given(planFeatureRepository.existsByPlanKeyAndFeatureKey(FeatureKeys.PLAN_FREE, key)).willReturn(false);
        given(entitlementRepository.existsActiveGrant(eq(EntitlementScopeKind.TEAM), eq(1L), eq(key), any()))
                .willReturn(true);   // T1 は契約あり
        given(entitlementRepository.existsActiveGrant(eq(EntitlementScopeKind.TEAM), eq(2L), eq(key), any()))
                .willReturn(false);  // T2 は無契約

        assertThat(service.isEntitled(EntitlementScopeKind.TEAM, 1L, key)).isTrue();
        assertThat(service.isEntitled(EntitlementScopeKind.TEAM, 2L, key)).isFalse();
    }

    @Test
    @DisplayName("AC-03: USER 個人契約は同一 featureKey の所属 TEAM に充当されない（scopeKind 隔離）")
    void ac03_userContractDoesNotApplyToTeam() {
        initService();
        String key = FeatureKeys.ADS_HIDE;
        given(featureCatalogRepository.findById(key)).willReturn(Optional.of(feature(key, true, false)));
        given(planFeatureRepository.existsByPlanKeyAndFeatureKey(FeatureKeys.PLAN_FREE, key)).willReturn(false);
        given(entitlementRepository.existsActiveGrant(eq(EntitlementScopeKind.USER), eq(50L), eq(key), any()))
                .willReturn(true);   // 個人 U=50 は契約あり
        given(entitlementRepository.existsActiveGrant(eq(EntitlementScopeKind.TEAM), eq(7L), eq(key), any()))
                .willReturn(false);  // U が所属する TEAM=7 には効かない

        assertThat(service.isEntitled(EntitlementScopeKind.USER, 50L, key)).isTrue();
        assertThat(service.isEntitled(EntitlementScopeKind.TEAM, 7L, key)).isFalse();
    }

    @Test
    @DisplayName("AC-05: FREE 掲載機能は契約ゼロのスコープでも true（existsActiveGrant を呼ばない）")
    void ac05_freePlanFeatureAlwaysTrue() {
        initService();
        String key = "chat.basic";
        given(featureCatalogRepository.findById(key)).willReturn(Optional.of(feature(key, true, false)));
        given(planFeatureRepository.existsByPlanKeyAndFeatureKey(FeatureKeys.PLAN_FREE, key)).willReturn(true);

        assertThat(service.isEntitled(EntitlementScopeKind.USER, 99L, key)).isTrue();
        verify(entitlementRepository, never()).existsActiveGrant(any(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("AC-18: 不明 feature_key は fail-safe で false（existsActiveGrant を呼ばない）")
    void ac18_unknownFeatureKeyFailSafeFalse() {
        initService();
        String key = "does.not.exist";
        given(featureCatalogRepository.findById(key)).willReturn(Optional.empty());

        assertThat(service.isEntitled(EntitlementScopeKind.TEAM, 10L, key)).isFalse();
        verify(entitlementRepository, never()).existsActiveGrant(any(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("AC-18: enabled=false の feature_key も fail-safe で false")
    void ac18_disabledFeatureKeyFailSafeFalse() {
        initService();
        String key = FeatureKeys.ADS_HIDE;
        given(featureCatalogRepository.findById(key)).willReturn(Optional.of(feature(key, false, false)));

        assertThat(service.isEntitled(EntitlementScopeKind.TEAM, 10L, key)).isFalse();
        verify(entitlementRepository, never()).existsActiveGrant(any(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("非営利無料枠: free_for_nonprofit かつ非営利スコープなら true（機構検証）")
    void nonprofitFreeGrantsWhenScopeIsNonProfit() {
        initService();
        String key = "internal.free_for_np";
        given(featureCatalogRepository.findById(key)).willReturn(Optional.of(feature(key, true, true)));
        given(planFeatureRepository.existsByPlanKeyAndFeatureKey(FeatureKeys.PLAN_FREE, key)).willReturn(false);
        given(scopeClassificationService.isNonProfitScope(EntitlementScopeKind.ORG, 5L)).willReturn(true);

        assertThat(service.isEntitled(EntitlementScopeKind.ORG, 5L, key)).isTrue();
        verify(entitlementRepository, never()).existsActiveGrant(any(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("AC-23: entitledFeatureKeys 集合＝isEntitled=true の集合（UI と BE の齟齬ゼロ・legacy 除外）")
    void ac23_entitledFeatureKeysMatchesIsEntitledSet() {
        initService();
        String freeKey = "chat.basic";          // FREE 掲載 → 常に true
        String ownedKey = FeatureKeys.ADS_HIDE; // entitlement あり → true
        String notOwned = FeatureKeys.TEMPLATE_PREMIUM_MODULES; // 権利なし → false
        String legacy = FeatureKeys.LEGACY_PAID_PLAN_BUNDLE;    // カタログ表示除外

        FeatureCatalogEntity fFree = feature(freeKey, true, false);
        FeatureCatalogEntity fOwned = feature(ownedKey, true, false);
        FeatureCatalogEntity fNot = feature(notOwned, true, false);
        FeatureCatalogEntity fLegacy = feature(legacy, true, false);
        given(featureCatalogRepository.findByEnabledTrueOrderBySortOrderAsc())
                .willReturn(List.of(fFree, fOwned, fNot, fLegacy));

        // isEntitled が参照する下位リポジトリのスタブ（自己呼び出しで実 isEntitled が走る）。
        given(featureCatalogRepository.findById(freeKey)).willReturn(Optional.of(fFree));
        given(featureCatalogRepository.findById(ownedKey)).willReturn(Optional.of(fOwned));
        given(featureCatalogRepository.findById(notOwned)).willReturn(Optional.of(fNot));
        given(planFeatureRepository.existsByPlanKeyAndFeatureKey(FeatureKeys.PLAN_FREE, freeKey)).willReturn(true);
        lenient().when(planFeatureRepository.existsByPlanKeyAndFeatureKey(FeatureKeys.PLAN_FREE, ownedKey))
                .thenReturn(false);
        lenient().when(planFeatureRepository.existsByPlanKeyAndFeatureKey(FeatureKeys.PLAN_FREE, notOwned))
                .thenReturn(false);
        given(entitlementRepository.existsActiveGrant(eq(EntitlementScopeKind.TEAM), eq(10L), eq(ownedKey), any()))
                .willReturn(true);
        given(entitlementRepository.existsActiveGrant(eq(EntitlementScopeKind.TEAM), eq(10L), eq(notOwned), any()))
                .willReturn(false);

        Set<String> entitled = service.entitledFeatureKeys(EntitlementScopeKind.TEAM, 10L);

        assertThat(entitled).containsExactlyInAnyOrder(freeKey, ownedKey);
        // 不変条件: 一覧の各キーは isEntitled=true・除外キーは false
        for (String key : entitled) {
            assertThat(service.isEntitled(EntitlementScopeKind.TEAM, 10L, key)).isTrue();
        }
        assertThat(entitled).doesNotContain(notOwned, legacy);
    }
}
