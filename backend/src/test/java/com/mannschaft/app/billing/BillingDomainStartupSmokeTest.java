package com.mannschaft.app.billing;

import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * F20.1 billing ドメインの起動スモークテスト（実 MySQL・CI 実行 / Docker 無しはスキップ）。
 *
 * <p><b>目的</b>: ApplicationContext とリポジトリ Bean が正常に起動することを確認し、
 * 派生クエリのメソッド名解決失敗（{@code PropertyReferenceException}）を起動時点で機械的に検出する
 * （検分観点 6）。実クエリを 1 本ずつ叩いて derived query / {@code @Query} の妥当性を確認する。</p>
 *
 * <p>共有ハーネス {@link AbstractMySqlIntegrationTest} を継承し TestContext Cache を 1 本化する
 * （新規 {@code @SpringBootTest} を増やさない・OOM 回避）。{@code @EnabledIf} は派生クラスで再宣言必須。</p>
 */
@DisplayName("F20.1 billing ドメイン起動スモーク（実 MySQL）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class BillingDomainStartupSmokeTest extends AbstractMySqlIntegrationTest {

    @Autowired private EntitlementRepository entitlementRepository;
    @Autowired private BillingContractRepository billingContractRepository;
    @Autowired private ActiveContractPointerRepository activeContractPointerRepository;
    @Autowired private FeatureCatalogRepository featureCatalogRepository;
    @Autowired private PlanRepository planRepository;
    @Autowired private PlanFeatureRepository planFeatureRepository;
    @Autowired private PlanPriceBandRepository planPriceBandRepository;

    @Autowired private EntitlementQueryService entitlementQueryService;
    @Autowired private EntitlementGuard entitlementGuard;
    @Autowired private BillingContractService billingContractService;
    @Autowired private ScopeMemberCountService scopeMemberCountService;
    @Autowired private ScopeClassificationService scopeClassificationService;
    @Autowired private EntitlementCacheEvictor entitlementCacheEvictor;

    @Test
    @DisplayName("全 Bean が起動し、派生クエリ/@Query が例外なく実行できる")
    void contextLoadsAndRepositoriesResolveQueries() {
        assertThat(entitlementRepository).isNotNull();
        assertThat(billingContractRepository).isNotNull();
        assertThat(activeContractPointerRepository).isNotNull();
        assertThat(entitlementQueryService).isNotNull();
        assertThat(entitlementGuard).isNotNull();
        assertThat(billingContractService).isNotNull();
        assertThat(scopeMemberCountService).isNotNull();
        assertThat(scopeClassificationService).isNotNull();
        assertThat(entitlementCacheEvictor).isNotNull();

        // 派生クエリ / @Query を 1 本ずつ実行（PropertyReferenceException / 不正 JPQL を起動時に検出）。
        assertThatCode(() -> {
            featureCatalogRepository.findByEnabledTrueOrderBySortOrderAsc();
            planRepository.findByEnabledTrueOrderBySortOrderAsc();
            planFeatureRepository.existsByPlanKeyAndFeatureKey("FREE", "chat.basic");
            planFeatureRepository.existsPurchasablePlanContaining(FeatureKeys.ADS_HIDE);
            planPriceBandRepository.findByPlanKeyAndScopeKindOrderByBandNoAsc("FULL", PlanPriceBandScopeKind.TEAM);
            entitlementRepository.existsActiveGrant(
                    EntitlementScopeKind.TEAM, 1L, FeatureKeys.ADS_HIDE, java.time.LocalDateTime.now());
            entitlementRepository.findActiveByScope(
                    EntitlementScopeKind.TEAM, 1L, java.time.LocalDateTime.now());
            billingContractRepository.findByScopeKindAndScopeIdAndStatusAndDeletedAtIsNull(
                    EntitlementScopeKind.TEAM, 1L, ContractStatus.ACTIVE);
            activeContractPointerRepository.findByScopeKindAndScopeIdAndContractKindAndAddonFeatureKey(
                    EntitlementScopeKind.TEAM, 1L, ContractKind.PLAN, "");
        }).doesNotThrowAnyException();

        // 注記: {@code entitlementQueryService.isEntitled} は {@code @Cacheable("entitlement:check")} ゆえ
        // 実行すると RedisCacheManager が Valkey(6379) へ接続する。CI のテストジョブは Redis を持たず
        // （test プロファイルは StringRedisTemplate をモック・実 Valkey は無し）、キャッシュ操作は
        // RedisConnectionFailureException になる。isEntitled の判定ロジックは pure UT
        // （EntitlementQueryServiceTest / EntitlementEntityActiveAtTest）で完全に担保済みのため、
        // 本起動スモークではキャッシュ操作を伴う呼び出しは行わない（本テストの責務は
        // ApplicationContext とリポジトリ Bean の起動＋派生クエリ/@Query の妥当性確認）。
    }
}
