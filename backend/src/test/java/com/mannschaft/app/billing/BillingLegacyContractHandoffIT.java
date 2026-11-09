package com.mannschaft.app.billing;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.billing.api.BillingContractApplicationService;
import com.mannschaft.app.billing.api.dto.CreateContractRequest;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

/**
 * CMP-260930-1931 AC12/14/27: 実Resolverで得たAと、後続実契約時のBのhandoff。
 * 同期hookは実Resolverの処理とTX復帰を終えた後にClockだけを進め、返値を変更しない。
 * 内部Bean/Guard/DBは実物。外部Stripe窓口だけstubし、実機成功の代用にはしない。
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@Import(BillingLegacyContractHandoffIT.HandoffConfiguration.class)
@DisplayName("1931 legacy契約handoffと保存前拒否")
class BillingLegacyContractHandoffIT extends AbstractMySqlIntegrationTest {

    private static final Instant BOUNDARY = Instant.parse("2026-11-09T00:00:00Z");
    private static final int A_PRICE = 1_100;
    private static final int B_PRICE = 3_630;

    @Autowired private PlanRepository plans;
    @Autowired private PlanFeatureRepository planFeatures;
    @Autowired private FeatureCatalogRepository features;
    @Autowired private BillingPriceVersionRepository versions;
    @Autowired private BillingPriceBandVersionRepository bands;
    @Autowired private BillingPriceResolver resolver;
    @Autowired private BillingPriceSelector selector;
    @Autowired private BillingCheckoutService checkout;
    @Autowired private BillingContractService contracts;
    @Autowired private BillingContractApplicationService application;
    @Autowired private BillingContractRepository contractRepository;
    @Autowired private ActiveContractPointerRepository pointers;
    @Autowired private EntitlementRepository entitlements;
    @Autowired private UserRepository users;
    @Autowired private TransactionTemplate transactions;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private BillingPaymentGateway gateway;
    @TestBean(name = "utcClock", methodName = "handoffClock") private Clock clock;

    private String planKey;
    private String featureKey;
    private Long actor;
    private final List<UUID> versionIds = new ArrayList<>();
    private final List<UUID> bandIds = new ArrayList<>();

    static Clock handoffClock() {
        return new HandoffClock(BOUNDARY.minusSeconds(1), ZoneOffset.UTC);
    }

    @BeforeEach
    void 所有actorと実Aと予約Bをcommitする() {
        time(BOUNDARY.minusSeconds(1));
        planKey = "I1931H-" + UUID.randomUUID().toString().substring(0, 16);
        featureKey = planKey.toLowerCase(java.util.Locale.ROOT) + ".grant";
        transactions.executeWithoutResult(tx -> {
            actor = users.saveAndFlush(UserEntity.builder()
                    .email("i1931-handoff-" + UUID.randomUUID() + "@example.com")
                    .lastName("試練").firstName("1931").displayName("1931handoff")
                    .status(UserEntity.UserStatus.ACTIVE).locale("ja").timezone("Asia/Tokyo")
                    .isSearchable(true).build()).getId();
            plans.saveAndFlush(PlanEntity.builder().planKey(planKey).baseMonthlyPriceJpy(A_PRICE)
                    .displayNameKey("it1931").descriptionKey("it1931").sortOrder(0).enabled(true).build());
            features.saveAndFlush(FeatureCatalogEntity.builder().featureKey(featureKey)
                    .category(FeatureCategory.INTERNAL).addonAvailable(false).freeForNonprofit(false)
                    .displayNameKey("it1931").descriptionKey("it1931").sortOrder(0).enabled(true).build());
            planFeatures.saveAndFlush(PlanFeatureEntity.builder().planKey(planKey).featureKey(featureKey).build());
            savePrice(1L, BillingPriceVersionStatus.ACTIVE, BOUNDARY.minusSeconds(86_400), BOUNDARY,
                    A_PRICE, 1_000L, 100L);
            savePrice(2L, BillingPriceVersionStatus.SCHEDULED, BOUNDARY, null, B_PRICE, 3_300L, 330L);
        });
        when(gateway.createSubscriptionCheckout(anyLong(), anyInt(), anyString(), any(UUID.class),
                anyString(), anyString())).thenReturn(new BillingPaymentGateway.CheckoutSessionInfo(
                        "cs_it1931_handoff", "https://example.invalid/1931-checkout"));
        clearInvocations(gateway);
    }

    @AfterEach
    void 所有契約とfixtureだけを撤去する() {
        ResolverReturnHook.CURRENT.remove();
        transactions.executeWithoutResult(tx -> {
            if (actor != null) {
                List<BillingContractEntity> owned = ownedContracts();
                for (BillingContractEntity contract : owned) {
                    entityManager.createQuery("delete from EntitlementEntity e where e.sourceRefId = :id")
                            .setParameter("id", contract.getId()).executeUpdate();
                    pointers.hardDeleteBySlotAndContractId(EntitlementScopeKind.USER, actor,
                            ContractKind.PLAN, "", contract.getId());
                }
                contractRepository.deleteAll(owned);
                contractRepository.flush();
            }
            bands.deleteAllById(bandIds);
            bands.flush();
            versions.deleteAllById(versionIds);
            versions.flush();
            if (planKey != null) {
                planFeatures.deleteAll(planFeatures.findByPlanKey(planKey));
                planFeatures.flush();
                plans.deleteById(planKey);
            }
            if (featureKey != null) features.deleteById(featureKey);
            if (actor != null) users.deleteById(actor);
        });
    }

    @Test
    void 実ResolverのAを渡した後で改定BになればCheckout保存前に019で拒否する() {
        Integer quoted = resolve();
        assertThat(quoted).isEqualTo(A_PRICE);
        time(BOUNDARY);

        assertError(() -> checkout.startPaidContract(EntitlementScopeKind.USER, actor, null,
                ContractKind.PLAN, planKey, null, quoted, actor), EntitlementErrorCode.PRICE_NOT_SELLABLE);

        assertNoContractSideEffects();
    }

    @Test
    void Applicationの実Resolver復帰後に改定境界を越えても旧AでPSPへ進めない() {
        AtomicReference<Object> observed = new AtomicReference<>();
        ResolverReturnHook.CURRENT.set(value -> {
            observed.set(value);
            time(BOUNDARY);
        });

        assertError(() -> application.create(EntitlementScopeKind.USER, actor, actor,
                new CreateContractRequest("PLAN", planKey, null), UUID.randomUUID().toString()),
                EntitlementErrorCode.PRICE_NOT_SELLABLE);

        assertThat(observed.get()).isEqualTo(A_PRICE);
        assertNoContractSideEffects();
    }

    @Test
    void Application無料判定の後に予約Bが開始したら017で権利発行前に拒否する() {
        removeCurrentPrice();
        transactions.executeWithoutResult(tx -> plans.findById(planKey).orElseThrow().setBaseMonthlyPriceJpy(0));
        assertThat(resolve()).isZero();
        AtomicReference<Object> observed = new AtomicReference<>();
        ResolverReturnHook.CURRENT.set(value -> {
            observed.set(value);
            time(BOUNDARY);
        });

        assertError(() -> application.create(EntitlementScopeKind.USER, actor, actor,
                new CreateContractRequest("PLAN", planKey, null), UUID.randomUUID().toString()),
                EntitlementErrorCode.CONTRACT_CHANGE_REQUIRES_PAYMENT);

        assertThat(observed.get()).isEqualTo(0);
        assertNoContractSideEffects();
    }

    @Test
    void 通常無料入口へ現在有料PLANを渡しても017で保存前に拒否する() {
        time(BOUNDARY);
        assertThat(selector.selectNow(BillingProductKind.PLAN, planKey, EntitlementScopeKind.USER)
                .orElseThrow().bands().getFirst().getAmountIncludingTax()).isEqualTo((long) B_PRICE);

        assertError(() -> contracts.createContract(EntitlementScopeKind.USER, actor, null,
                ContractKind.PLAN, planKey, null, actor), EntitlementErrorCode.CONTRACT_CHANGE_REQUIRES_PAYMENT);

        assertNoContractSideEffects();
    }

    @Test
    void 旧有料だけで新公開価格がなければpaid入口も040で保存前に拒否する() {
        transactions.executeWithoutResult(tx -> {
            bands.deleteAllById(bandIds);
            bands.flush();
            versions.deleteAllById(versionIds);
            versions.flush();
        });

        assertError(() -> contracts.createPendingPaidContract(EntitlementScopeKind.USER, actor, null,
                ContractKind.PLAN, planKey, null, A_PRICE, actor), EntitlementErrorCode.PLAN_PRICE_NOT_CONFIGURED);

        assertNoContractSideEffects();
    }

    /** 初回greenが正常の契約characterization。USERはmapperのbandNoを呼ばずnullを維持する。 */
    @Test
    void USERの現在Bと一致するpaid契約は額Bとnull帯を保存しPSPへ一回進む() {
        time(BOUNDARY);
        BillingCheckoutService.PaidCheckoutResult result = checkout.startPaidContract(
                EntitlementScopeKind.USER, actor, null, ContractKind.PLAN, planKey, null, B_PRICE, actor);

        transactions.executeWithoutResult(tx -> {
            BillingContractEntity saved = contractRepository.findById(result.pending().contractId()).orElseThrow();
            assertThat(saved.getStatus()).isEqualTo(ContractStatus.PENDING);
            assertThat(saved.getPriceJpySnapshot()).isEqualTo(B_PRICE);
            assertThat(saved.getBandNoSnapshot()).isNull();
            assertThat(saved.getMemberCountSnapshot()).isNull();
            assertThat(pointers.findByScopeKindAndScopeIdAndContractKindAndAddonFeatureKey(
                    EntitlementScopeKind.USER, actor, ContractKind.PLAN, "").orElseThrow().getContractId())
                    .isEqualTo(saved.getId());
            assertThat(entitlements.findBySourceKindAndSourceRefIdAndRevokedAtIsNull(
                    EntitlementSourceKind.PLAN, saved.getId())).isEmpty();
        });
        assertThat(pspCreates()).isEqualTo(1);
    }

    /** SYSTEM_ADMINの専用Java入口は通常無料入口の価格guardを共有しない既存互換。 */
    @Test
    void SYSTEM_ADMIN手動付与は有料モデルでも従来のnull額と権利を保存する() {
        time(BOUNDARY);
        BillingContractService.ContractResult result = contracts.createContractBySystemAdmin(
                EntitlementScopeKind.USER, actor, null, ContractKind.PLAN, planKey, null, actor);

        transactions.executeWithoutResult(tx -> {
            BillingContractEntity saved = contractRepository.findById(result.contractId()).orElseThrow();
            assertThat(saved.getStatus()).isEqualTo(ContractStatus.ACTIVE);
            assertThat(saved.getPriceJpySnapshot()).isNull();
            assertThat(entitlements.findBySourceKindAndSourceRefIdAndRevokedAtIsNull(
                    EntitlementSourceKind.PLAN, saved.getId())).extracting(EntitlementEntity::getFeatureKey)
                    .containsExactly(featureKey);
        });
        assertThat(pspCreates()).isZero();
    }

    /** 既存互換のrollback校正。実pointer flush後の権利INSERTをMySQLが拒否する。 */
    @Test
    void 実DBが権利発行途中で失敗したら契約とpointerもrollbackする() {
        transactions.executeWithoutResult(tx -> {
            bands.deleteAllById(bandIds);
            bands.flush();
            versions.deleteAllById(versionIds);
            versions.flush();
            plans.findById(planKey).orElseThrow().setBaseMonthlyPriceJpy(0);
        });
        String trigger = "it1931h_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON entitlements FOR EACH ROW BEGIN "
                + "IF NEW.feature_key = '" + featureKey + "' THEN "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'it1931-owned-entitlement-failure'; END IF; END");
        try {
            assertThatThrownBy(() -> contracts.createContract(EntitlementScopeKind.USER, actor, null,
                    ContractKind.PLAN, planKey, null, actor)).isInstanceOf(DataAccessException.class);
            assertNoContractSideEffects();
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS " + trigger);
        }
    }

    private Integer resolve() {
        return resolver.resolveMonthlyPriceJpy(EntitlementScopeKind.USER, actor, ContractKind.PLAN, planKey, null);
    }

    private void assertError(Runnable action, EntitlementErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode()).isEqualTo(expected);
    }

    private void assertNoContractSideEffects() {
        transactions.executeWithoutResult(tx -> {
            assertThat(ownedContracts()).isEmpty();
            assertThat(pointers.findByScopeKindAndScopeIdAndContractKindAndAddonFeatureKey(
                    EntitlementScopeKind.USER, actor, ContractKind.PLAN, "")).isEmpty();
            assertThat(entitlements.findActiveByScope(EntitlementScopeKind.USER, actor,
                    java.time.LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))).isEmpty();
        });
        assertThat(pspCreates()).isZero();
    }

    private List<BillingContractEntity> ownedContracts() {
        return contractRepository.findByScopeKindAndScopeIdAndDeletedAtIsNullOrderByContractedAtDesc(
                EntitlementScopeKind.USER, actor);
    }

    private long pspCreates() {
        return mockingDetails(gateway).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("createSubscriptionCheckout")).count();
    }

    private void removeCurrentPrice() {
        transactions.executeWithoutResult(tx -> {
            bands.deleteById(bandIds.getFirst());
            bands.flush();
            versions.deleteById(versionIds.getFirst());
            versions.flush();
        });
        bandIds.removeFirst();
        versionIds.removeFirst();
    }

    private void savePrice(long revision, BillingPriceVersionStatus status, Instant from, Instant until,
                           long total, long excluding, long tax) {
        BillingPriceVersionEntity version = versions.saveAndFlush(BillingPriceVersionEntity.builder()
                .productKind(BillingProductKind.PLAN).productKey(planKey).scopeKind(EntitlementScopeKind.USER)
                .catalogRevision("1931-handoff-" + revision).revisionNo(revision).status(status)
                .effectiveFrom(from).effectiveUntil(until).provisionAttempts(0)
                .creationSource(BillingPriceCreationSource.SYSTEM_BACKFILL).build());
        versionIds.add(version.getId());
        bandIds.add(bands.saveAndFlush(BillingPriceBandVersionEntity.builder()
                .productKind(BillingProductKind.PLAN).productKey(planKey).scopeKind(EntitlementScopeKind.USER)
                .priceVersionId(version.getId()).bandNo(1).minMembers(1).currency("JPY")
                .stripePriceRef("price_it1931_handoff_" + UUID.randomUUID()).inputAmount(total)
                .taxBehavior(BillingTaxBehavior.INCLUSIVE).taxCodeSnapshot("txcd_10000000").taxMasterSnapshot("{}")
                .amountExcludingTax(excluding).taxAmount(tax).amountIncludingTax(total).taxRateBasisPoints(1_000)
                .taxNameSnapshot("消費税").includedInPrice(true).effectiveFrom(from).effectiveUntil(until)
                .status(status).creationSource(BillingPriceCreationSource.SYSTEM_BACKFILL).build()).getId());
    }

    private void time(Instant at) {
        ((HandoffClock) clock).at.set(at);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class HandoffConfiguration {
        @Bean ResolverReturnHook resolverReturnHook() { return new ResolverReturnHook(); }
    }

    /** 実戻り値をそのまま返す、現スレッド一回限りの同期hook。 */
    @Aspect
    @Order(Ordered.HIGHEST_PRECEDENCE + 100)
    static class ResolverReturnHook {
        private static final ThreadLocal<java.util.function.Consumer<Object>> CURRENT = new ThreadLocal<>();

        @Around("execution(* com.mannschaft.app.billing.BillingPriceResolver.resolveMonthlyPriceJpy(..))")
        Object afterActualReturn(ProceedingJoinPoint invocation) throws Throwable {
            Object value = invocation.proceed();
            var hook = CURRENT.get();
            if (hook != null) {
                CURRENT.remove();
                hook.accept(value);
            }
            return value;
        }
    }

    /** 時点だけを進める実Clock。zone派生も同じ時点参照を共有する。 */
    private static final class HandoffClock extends Clock {
        private final AtomicReference<Instant> at;
        private final ZoneId zone;
        private HandoffClock(Instant instant, ZoneId zone) { this(new AtomicReference<>(instant), zone); }
        private HandoffClock(AtomicReference<Instant> at, ZoneId zone) { this.at = at; this.zone = zone; }
        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId newZone) { return new HandoffClock(at, newZone); }
        @Override public Instant instant() { return at.get(); }
    }
}
