package com.mannschaft.app.billing;

import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;

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

        // AC: plan_price_bands の実在行を「保存→再読込」できること（PlanPriceBandId 型不整合の再発防止）。
        //
        // <p>注記: test プロファイルは {@code flyway.enabled=false} かつ {@code ddl-auto=create}
        // （application-test.yml）であり、Flyway seed（本番の plan_price_bands 初期データ）は
        // テスト DB に一切投入されない。そのため既存の派生クエリ呼び出しだけでは実データの 0 件
        // クエリにしかならず、Hibernate が複合 ID を組み立てる経路（{@code @IdClass} のフィールド型
        // 不一致で {@code InstantiationException} を起こす経路）を一度も通らずに素通りしていた
        // （本番 500 が CI で捕まらなかった真因）。ここでは行を自前で 1 件 persist してから
        // 同じ派生クエリで再読込し、実際にエンティティを hydrate させて経路を通す。</p>
        PlanPriceBandEntity fixture = PlanPriceBandEntity.builder()
                .planKey("FULL")
                .scopeKind(PlanPriceBandScopeKind.TEAM)
                .bandNo((short) 90)
                .minMembers(1)
                .maxMembers(null)
                .monthlyPriceJpy(null)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        assertThatCode(() -> {
            planPriceBandRepository.saveAndFlush(fixture);
            List<PlanPriceBandEntity> reloaded =
                    planPriceBandRepository.findByPlanKeyAndScopeKindOrderByBandNoAsc("FULL", PlanPriceBandScopeKind.TEAM);
            assertThat(reloaded)
                    .extracting(PlanPriceBandEntity::getScopeKind)
                    .contains(PlanPriceBandScopeKind.TEAM);
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
