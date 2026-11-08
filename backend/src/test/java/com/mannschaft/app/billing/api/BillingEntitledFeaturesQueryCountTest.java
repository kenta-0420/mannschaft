package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.BillingContractChangeRepository;
import com.mannschaft.app.billing.BillingContractRepository;
import com.mannschaft.app.billing.EntitlementQueryService;
import com.mannschaft.app.billing.EntitlementRepository;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.FeatureCatalogRepository;
import com.mannschaft.app.billing.FeatureKeys;
import com.mannschaft.app.billing.PlanFeatureEntity;
import com.mannschaft.app.billing.PlanFeatureRepository;
import com.mannschaft.app.billing.PlanRepository;
import com.mannschaft.app.billing.ScopeMemberCountService;
import com.mannschaft.app.billing.api.dto.EntitlementSummaryResponse;
import com.mannschaft.app.billing.api.dto.EntitledFeature;
import com.mannschaft.app.common.AccessControlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 権利サマリの entitledFeatures 合成における FREE 掲載判定の問い合わせ回数を固定する
 * （戦役 CMP-261103-1225 / #4270 P1-A、AC-1〜AC-3）。契約行の無いキー数に依存せず、
 * FREE プランのキー集合を 1 回だけ取得して判定する。DB 実測の IT は
 * {@link BillingEntitledFeaturesN1IT}。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("権利サマリ entitledFeatures: FREE 掲載判定の問い合わせ回数")
class BillingEntitledFeaturesQueryCountTest {

    @Mock private EntitlementQueryService entitlementQueryService;
    @Mock private EntitlementRepository entitlementRepository;
    @Mock private BillingContractRepository billingContractRepository;
    @Mock private BillingContractChangeRepository billingContractChangeRepository;
    @Mock private FeatureCatalogRepository featureCatalogRepository;
    @Mock private PlanFeatureRepository planFeatureRepository;
    @Mock private PlanRepository planRepository;
    @Mock private AccessControlService accessControlService;
    @Mock private BillingCurrentBandResolver currentBandResolver;
    @Mock private ScopeMemberCountService scopeMemberCountService;

    private BillingEntitlementQueryService service;

    @BeforeEach
    void setUp() {
        service = new BillingEntitlementQueryService(
                entitlementQueryService, entitlementRepository, billingContractRepository,
                billingContractChangeRepository, featureCatalogRepository, planFeatureRepository,
                planRepository, accessControlService, currentBandResolver, scopeMemberCountService,
                Clock.fixed(Instant.parse("2026-11-03T00:00:00Z"), ZoneId.of("Asia/Tokyo")));
        lenient().when(billingContractRepository.findByScopeKindAndScopeIdAndStatusInAndDeletedAtIsNull(
                any(), any(), anyList())).thenReturn(List.of());
        lenient().when(entitlementRepository.findActiveByScope(any(), any(), any())).thenReturn(List.of());
        lenient().when(planFeatureRepository.existsByPlanKeyAndFeatureKey(anyString(), anyString()))
                .thenAnswer(inv -> planFeatureKeys.contains(inv.<String>getArgument(1)));
        lenient().when(planFeatureRepository.findByPlanKey(FeatureKeys.PLAN_FREE))
                .thenAnswer(inv -> planFeatureKeys.stream().map(this::free).toList());
    }

    private Set<String> planFeatureKeys = Set.of();

    private PlanFeatureEntity free(String key) {
        return PlanFeatureEntity.builder().planKey(FeatureKeys.PLAN_FREE).featureKey(key).build();
    }

    private Set<String> keys(String prefix, int n) {
        Set<String> s = new LinkedHashSet<>();
        for (int i = 0; i < n; i++) {
            s.add(prefix + i);
        }
        return s;
    }

    @Test
    @DisplayName("AC-1/AC-2: virtual キーが25件でも FREE 掲載の取得は1回。sourceKind と順序は従前どおり")
    void freeLookupIsOneQueryRegardlessOfKeyCount() {
        Set<String> free = keys("free.", 20);
        Set<String> nonprofit = keys("np.", 5);
        planFeatureKeys = free;
        Set<String> entitled = new LinkedHashSet<>(nonprofit);
        entitled.addAll(free);
        when(entitlementQueryService.entitledFeatureKeys(EntitlementScopeKind.USER, 1L)).thenReturn(entitled);

        EntitlementSummaryResponse res = service.getSummary(EntitlementScopeKind.USER, 1L);

        assertThat(res.getEntitledFeatures()).extracting(EntitledFeature::getFeatureKey)
                .containsExactlyElementsOf(entitled);
        assertThat(res.getEntitledFeatures()).filteredOn(f -> "FREE".equals(f.getSourceKind())).hasSize(20);
        assertThat(res.getEntitledFeatures()).filteredOn(f -> "NONPROFIT_FREE".equals(f.getSourceKind()))
                .hasSize(5);
        verify(planFeatureRepository, never()).existsByPlanKeyAndFeatureKey(anyString(), anyString());
        verify(planFeatureRepository, times(1)).findByPlanKey(FeatureKeys.PLAN_FREE);
    }

    @Test
    @DisplayName("AC-3: entitled キーが0件なら FREE 掲載を問い合わせず空配列")
    void emptyEntitledKeysQueriesNothing() {
        when(entitlementQueryService.entitledFeatureKeys(EntitlementScopeKind.USER, 1L)).thenReturn(Set.of());

        assertThat(service.getSummary(EntitlementScopeKind.USER, 1L).getEntitledFeatures()).isEmpty();
        verify(planFeatureRepository, never()).findByPlanKey(anyString());
        verify(planFeatureRepository, never()).existsByPlanKeyAndFeatureKey(anyString(), anyString());
    }
}
