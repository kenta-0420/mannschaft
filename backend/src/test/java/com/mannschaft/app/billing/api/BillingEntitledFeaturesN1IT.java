package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.EntitlementQueryService;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.FeatureKeys;
import com.mannschaft.app.billing.PlanFeatureEntity;
import com.mannschaft.app.billing.PlanFeatureRepository;
import com.mannschaft.app.billing.api.dto.EntitlementSummaryResponse;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;

/**
 * 権利サマリの entitledFeatures 合成（BillingEntitlementQueryService#buildEntitledFeatures）の
 * N+1 解消を固定する試練（戦役 CMP-261103-1225 / #4270 P1-A、AC-1〜AC-3）。
 *
 * <p>契約行の無い entitled な機能キー（FREE 由来 / 非営利枠由来の virtual）の数を増やしても、
 * サマリ組み立ての SQL 数が増えないことを検証する。カタログ走査（entitledFeatureKeys）は別系統なので
 * スパイで固定し、本テストは buildEntitledFeatures の FREE 掲載判定だけを観測する。</p>
 */
@Transactional
@DisplayName("権利サマリ entitledFeatures N+1 解消（CMP-261103-1225 P1-A）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class BillingEntitledFeaturesN1IT extends AbstractMySqlIntegrationTest {

    private static final EntitlementScopeKind KIND = EntitlementScopeKind.USER;
    private static final long SCOPE_ID = 940001L;

    @Autowired private BillingEntitlementQueryService service;
    @Autowired private PlanFeatureRepository planFeatureRepository;
    @MockitoSpyBean private EntitlementQueryService entitlementQueryService;
    @PersistenceContext private EntityManager em;

    private Set<String> seedFreeKeys(String prefix, int n) {
        Set<String> keys = new LinkedHashSet<>();
        for (int i = 0; i < n; i++) {
            String key = prefix + i;
            keys.add(key);
            planFeatureRepository.save(PlanFeatureEntity.builder()
                    .planKey(FeatureKeys.PLAN_FREE).featureKey(key).build());
        }
        em.flush();
        return keys;
    }

    private long countSql(Set<String> entitled, Runnable r) {
        doReturn(entitled).when(entitlementQueryService).entitledFeatureKeys(KIND, SCOPE_ID);
        em.clear();
        Statistics s = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        s.setStatisticsEnabled(true);
        s.clear();
        r.run();
        return s.getPrepareStatementCount();
    }

    @Test
    @DisplayName("AC-1/AC-2: FREE 由来の virtual キー数を 1→25 に増やしても SQL 数が一定。sourceKind と順序は従前どおり")
    void sqlCountIndependentOfVirtualKeys() {
        Set<String> small = seedFreeKeys("n1.small.", 1);
        Set<String> large = seedFreeKeys("n1.large.", 25);

        long smallSql = countSql(small, () -> {
            EntitlementSummaryResponse r = service.getSummary(KIND, SCOPE_ID);
            assertThat(r.getEntitledFeatures()).hasSize(1);
        });
        long largeSql = countSql(large, () -> {
            EntitlementSummaryResponse r = service.getSummary(KIND, SCOPE_ID);
            assertThat(r.getEntitledFeatures()).hasSize(25);
            assertThat(r.getEntitledFeatures()).extracting(f -> f.getFeatureKey())
                    .containsExactlyElementsOf(large);
            assertThat(r.getEntitledFeatures()).allSatisfy(f -> {
                assertThat(f.getSourceKind()).isEqualTo("FREE");
                assertThat(f.getValidUntil()).isNull();
            });
        });

        System.out.println("BILLING SQL small=" + smallSql + " large=" + largeSql);
        assertThat(largeSql).as("virtual キー25件でも1件と同じ SQL 回数").isEqualTo(smallSql);
    }

    @Test
    @DisplayName("AC-2: FREE 掲載外の virtual キーは NONPROFIT_FREE、FREE 掲載は FREE。混在でも順序維持")
    void mixedFreeAndNonprofit() {
        Set<String> free = seedFreeKeys("n1.mix.free.", 2);
        Set<String> keys = new LinkedHashSet<>();
        keys.add("n1.mix.np.0");
        keys.addAll(free);
        keys.add("n1.mix.np.1");
        doReturn(keys).when(entitlementQueryService).entitledFeatureKeys(KIND, SCOPE_ID);
        em.clear();

        List<String> kinds = service.getSummary(KIND, SCOPE_ID).getEntitledFeatures().stream()
                .map(f -> f.getFeatureKey() + "=" + f.getSourceKind()).toList();

        assertThat(kinds).containsExactly(
                "n1.mix.np.0=NONPROFIT_FREE", "n1.mix.free.0=FREE", "n1.mix.free.1=FREE",
                "n1.mix.np.1=NONPROFIT_FREE");
    }

    @Test
    @DisplayName("AC-3: entitled キーが0件でも例外なく空配列を返す（空 IN を発行しない）")
    void emptyEntitledKeys() {
        doReturn(Set.of()).when(entitlementQueryService).entitledFeatureKeys(KIND, SCOPE_ID);

        assertThat(service.getSummary(KIND, SCOPE_ID).getEntitledFeatures()).isEmpty();
    }
}
