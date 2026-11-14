package com.mannschaft.app.billing;

import com.mannschaft.app.billing.api.BillingCatalogQueryService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CMP-260930-1931 AC07/08/11の先行試練。
 * 実Clockの時点を移動し、実Bean/MySQL/commitを通るlegacy入口の遅延昇格を検証する。
 * 実接続routing・中途失敗の証明は別試練とし、本試験の成功へ混同しない。
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("1931 legacy PLAN価格の時点・遅延昇格")
@Import(BillingLegacyPlanPromotionIT.RaceObservationConfiguration.class)
class BillingLegacyPlanPromotionIT extends AbstractMySqlIntegrationTest {

    private static final Instant DEFAULT_BOUNDARY = Instant.parse("2026-11-30T15:00:00Z");
    private static final int LEGACY_PRICE = 1_100;
    private static final int CURRENT_PRICE = 2_200;
    private static final int NEXT_PRICE = 3_630;
    private static final long TIMEOUT_SECONDS = 15L;

    @Autowired private PlanRepository planRepository;
    @Autowired private BillingPriceVersionRepository versions;
    @Autowired private BillingPriceBandVersionRepository bands;
    @Autowired private BillingCatalogQueryService catalog;
    @Autowired private BillingPriceResolver resolver;
    @Autowired private TransactionTemplate transactions;
    @Autowired private RaceObservationHook raceHook;
    @Autowired private BillingPriceSelector priceSelector;
    @TestBean(name = "utcClock", methodName = "promotionClock") private Clock clock;

    private String planKey;
    private UUID currentId;
    private UUID nextId;
    private UUID anotherDueId;
    private final List<UUID> bandIds = new ArrayList<>();

    static Clock promotionClock() {
        return new MutablePriceClock(DEFAULT_BOUNDARY, ZoneOffset.UTC);
    }

    @BeforeEach
    void 所有keyと実Clockを初期化する() {
        planKey = "I1931P-" + UUID.randomUUID().toString().substring(0, 16);
        time(DEFAULT_BOUNDARY);
    }

    @AfterEach
    void 所有した価格とClockだけを片付ける() {
        transactions.executeWithoutResult(tx -> {
            bands.deleteAllById(bandIds);
            bands.flush();
            if (currentId != null) {
                versions.deleteById(currentId);
            }
            if (nextId != null) {
                versions.deleteById(nextId);
            }
            if (anotherDueId != null) {
                versions.deleteById(anotherDueId);
            }
            versions.flush();
            if (planRepository.existsById(planKey)) {
                planRepository.deleteById(planKey);
            }
        });
        time(DEFAULT_BOUNDARY);
    }

    /** AC07/08。月末/年末の同InstantをUTC/JST双方で独立して実行する。 */
    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @CsvSource({
            "catalog,2026-11-30T15:00:00Z,UTC,-1", "catalog,2026-11-30T15:00:00Z,UTC,0",
            "catalog,2026-11-30T15:00:00Z,UTC,1", "catalog,2026-11-30T15:00:00Z,Asia/Tokyo,-1",
            "catalog,2026-11-30T15:00:00Z,Asia/Tokyo,0", "catalog,2026-11-30T15:00:00Z,Asia/Tokyo,1",
            "catalog,2026-12-31T15:00:00Z,UTC,-1", "catalog,2026-12-31T15:00:00Z,UTC,0",
            "catalog,2026-12-31T15:00:00Z,UTC,1", "catalog,2026-12-31T15:00:00Z,Asia/Tokyo,-1",
            "catalog,2026-12-31T15:00:00Z,Asia/Tokyo,0", "catalog,2026-12-31T15:00:00Z,Asia/Tokyo,1",
            "resolver,2026-11-30T15:00:00Z,UTC,-1", "resolver,2026-11-30T15:00:00Z,UTC,0",
            "resolver,2026-11-30T15:00:00Z,UTC,1", "resolver,2026-11-30T15:00:00Z,Asia/Tokyo,-1",
            "resolver,2026-11-30T15:00:00Z,Asia/Tokyo,0", "resolver,2026-11-30T15:00:00Z,Asia/Tokyo,1",
            "resolver,2026-12-31T15:00:00Z,UTC,-1", "resolver,2026-12-31T15:00:00Z,UTC,0",
            "resolver,2026-12-31T15:00:00Z,UTC,1", "resolver,2026-12-31T15:00:00Z,Asia/Tokyo,-1",
            "resolver,2026-12-31T15:00:00Z,Asia/Tokyo,0", "resolver,2026-12-31T15:00:00Z,Asia/Tokyo,1"})
    void 半開期間の境界でAからBへ一度だけ切り替わる(
            String entry, Instant boundary, String timezone, int offsetSeconds) {
        createPrices(boundary);
        time(boundary.plusSeconds(offsetSeconds));
        TimeZone previous = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(timezone));

            assertThat(readPrice(entry)).isEqualTo(offsetSeconds < 0 ? CURRENT_PRICE : NEXT_PRICE);
            assertStates(offsetSeconds < 0 ? BillingPriceVersionStatus.ACTIVE : BillingPriceVersionStatus.RETIRED,
                    offsetSeconds < 0 ? BillingPriceVersionStatus.SCHEDULED : BillingPriceVersionStatus.ACTIVE);
        } finally {
            TimeZone.setDefault(previous);
        }
    }

    /** AC07。effectiveUntilちょうどのAを復活させず、ACTIVE不在を販売不能にする。 */
    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @ValueSource(strings = {"catalog", "resolver"})
    void 期限ちょうどのAは新たな適格版が無ければ販売しない(String entry) {
        createPrices(DEFAULT_BOUNDARY);
        transactions.executeWithoutResult(tx -> {
            List<BillingPriceBandVersionEntity> nextBands = bands
                    .findByPriceVersionIdAndDeletedAtIsNullOrderByBandNoAsc(nextId);
            bands.deleteAll(nextBands);
            bands.flush();
            versions.deleteById(nextId);
            versions.flush();
        });
        time(DEFAULT_BOUNDARY);

        if ("catalog".equals(entry)) {
            assertThat(readPrice(entry)).isNull();
        } else {
            assertThatThrownBy(() -> readPrice(entry)).isInstanceOf(BusinessException.class)
                    .extracting(error -> ((BusinessException) error).getErrorCode())
                    .isEqualTo(EntitlementErrorCode.PLAN_PRICE_NOT_CONFIGURED);
        }
    }

    /** AC11の既存DB制約characterization。複数dueは現schemaでは保存不能。 */
    @Test
    void 二つ目のSCHEDULEDは一意制約で拒否され元の親子を変更しない() {
        createPrices(DEFAULT_BOUNDARY);

        assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> anotherDueId = savePrice(
                3L, BillingPriceVersionStatus.SCHEDULED, DEFAULT_BOUNDARY, null, 4_400L, 4_000L, 400L)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertStates(BillingPriceVersionStatus.ACTIVE, BillingPriceVersionStatus.SCHEDULED);
        transactions.executeWithoutResult(tx -> assertThat(versions.findByProductKindAndProductKeyAndScopeKindAndDeletedAtIsNullOrderByRevisionNoDesc(
                BillingProductKind.PLAN, planKey, EntitlementScopeKind.USER)).hasSize(2));
    }

    /** AC11。内部Beanの差替えを行わず、legacy入口の同時/反復読取で状態を再読込する。 */
    @Test
    void 同じdueを同時と反復で読んでもBからAへ戻らない() throws Exception {
        createPrices(DEFAULT_BOUNDARY);
        time(DEFAULT_BOUNDARY);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> first = executor.submit(() -> {
                await(start);
                return readPrice("catalog");
            });
            Future<Integer> second = executor.submit(() -> {
                await(start);
                return readPrice("resolver");
            });
            start.countDown();

            assertThat(first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(NEXT_PRICE);
            assertThat(second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(NEXT_PRICE);
            assertThat(readPrice("catalog")).isEqualTo(NEXT_PRICE);
            assertThat(readPrice("resolver")).isEqualTo(NEXT_PRICE);
            assertStates(BillingPriceVersionStatus.RETIRED, BillingPriceVersionStatus.ACTIVE);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }

    /** 実probe完了後のlock境界を固定し、終了時中断を競合の失敗へ混ぜない。 */
    @ParameterizedTest(name = "{displayName} [{index}] modern={arguments}")
    @ValueSource(booleans = {false, true})
    void 両実probe後に先行commitを挟んでも後行の価格読取はBを返す(boolean modern) throws Exception {
        createPrices(DEFAULT_BOUNDARY);
        time(DEFAULT_BOUNDARY);
        RaceObservation state = new RaceObservation(planKey, nextId);
        raceHook.current = state;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ReadOutcome firstOutcome;
        ReadOutcome secondOutcome;
        boolean settled;
        try {
            Future<Integer> first = executor.submit(() -> {
                state.lane.set("catalog");
                try {
                    Integer price = readConcurrentPrice(modern, "catalog"); // Spring proxy外のreturn後なのでcommit済み。
                    state.afterFirstCommit = freshVersions();
                    return price;
                } finally {
                    state.firstCompleted.countDown();
                    state.lane.remove();
                }
            });
            Future<Integer> second = executor.submit(() -> {
                state.lane.set("resolver");
                try {
                    return readConcurrentPrice(modern, "resolver");
                } finally {
                    state.lane.remove();
                }
            });
            firstOutcome = outcome(first);
            secondOutcome = outcome(second);
        } finally {
            state.firstCompleted.countDown();
            executor.shutdownNow();
            settled = executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            raceHook.current = null;
        }
        VersionObservation finalVersions = freshVersions();
        Map<String, Object> evidence = new java.util.LinkedHashMap<>();
        evidence.put("hibernateVersion", org.hibernate.Version.getVersionString());
        evidence.put("hibernateJar", java.nio.file.Path.of(org.hibernate.Version.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).getFileName().toString());
        evidence.put("modern", modern);
        evidence.put("callerMethods", modern ? Map.of("catalog", "BillingPriceSelector.selectNow",
                "resolver", "BillingPriceSelector.selectNow") : Map.of("catalog", "BillingCatalogQueryService.getCatalog",
                "resolver", "BillingPriceResolver.resolveMonthlyPriceJpy"));
        evidence.put("entityProbes", state.entityProbes);
        evidence.put("scalarProbes", state.scalarProbes);
        evidence.put("beforeLocks", state.beforeLocks);
        evidence.put("afterFirstCommit", state.afterFirstCommit);
        evidence.put("firstOutcome", firstOutcome);
        evidence.put("secondOutcome", secondOutcome);
        evidence.put("finalVersions", finalVersions);
        evidence.put("executorSettled", settled);
        String payload = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(evidence);
        System.out.println("IT1931_DUE_RACE " + java.util.Base64.getEncoder()
                .encodeToString(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        assertThat(settled).isTrue();
        assertThat(state.beforeLocks.keySet()).containsExactlyInAnyOrder("catalog", "resolver");
        assertThat(state.afterFirstCommit).isNotNull();
        assertThat(state.afterFirstCommit.currentStatus()).isEqualTo(BillingPriceVersionStatus.RETIRED);
        assertThat(state.afterFirstCommit.nextStatus()).isEqualTo(BillingPriceVersionStatus.ACTIVE);
        assertThat(state.afterFirstCommit.nextVersion()).isEqualTo(1L);
        assertThat(state.entityProbes).isEmpty();
        assertThat(state.scalarProbes.keySet()).containsExactlyInAnyOrder("catalog", "resolver");
        assertThat(state.scalarProbes.values()).allSatisfy(probe -> {
            assertThat(probe.count()).isEqualTo(1L);
            assertThat(probe.managedEntityCount()).isZero();
            assertThat(probe.transactionActive()).isTrue();
            assertThat(probe.readOnly()).isFalse();
        });
        assertThat(firstOutcome.failureType()).as("先行callerの実結果").isNull();
        assertThat(firstOutcome.value()).isEqualTo(NEXT_PRICE);
        assertThat(secondOutcome.failureType()).as("先行commit後の後行caller実結果").isNull();
        assertThat(secondOutcome.value()).isEqualTo(NEXT_PRICE);
        assertThat(finalVersions).isEqualTo(state.afterFirstCommit);
        assertThat(readConcurrentPrice(modern, "catalog")).isEqualTo(NEXT_PRICE);
        assertThat(readConcurrentPrice(modern, "resolver")).isEqualTo(NEXT_PRICE);
        assertThat(freshVersions()).isEqualTo(finalVersions);
    }

    private Integer readConcurrentPrice(boolean modern, String entry) {
        if (!modern) return readPrice(entry);
        return Math.toIntExact(priceSelector.selectNow(BillingProductKind.PLAN, planKey, EntitlementScopeKind.USER)
                .orElseThrow().bands().getFirst().getAmountIncludingTax());
    }

    private VersionObservation freshVersions() {
        return transactions.execute(tx -> {
            BillingPriceVersionEntity first = versions.findById(currentId).orElseThrow();
            BillingPriceVersionEntity next = versions.findById(nextId).orElseThrow();
            return new VersionObservation(first.getStatus(), first.getLockVersion(), next.getStatus(), next.getLockVersion());
        });
    }

    private static ReadOutcome outcome(Future<Integer> future) throws InterruptedException {
        try {
            return new ReadOutcome(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS), null, null);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            Throwable deepest = cause;
            for (int depth = 0; deepest.getCause() != null && depth < 20; depth++) deepest = deepest.getCause();
            return new ReadOutcome(null, cause.getClass().getName(), deepest.getClass().getName());
        } catch (TimeoutException timeout) {
            return new ReadOutcome(null, "harness-timeout", timeout.getClass().getName());
        } catch (java.util.concurrent.CancellationException cancelled) {
            return new ReadOutcome(null, "harness-cancelled", cancelled.getClass().getName());
        }
    }

    private record ReadOutcome(Integer value, String failureType, String deepestCause) { }
    private record VersionObservation(BillingPriceVersionStatus currentStatus, Long currentVersion,
                                      BillingPriceVersionStatus nextStatus, Long nextVersion) { }
    private record ProbeObservation(UUID id, Long version, boolean managed, String lockMode,
                                    boolean transactionActive, boolean readOnly) { }
    private record LockObservation(boolean transactionActive, boolean readOnly) { }
    private record ScalarProbeObservation(long count, int managedEntityCount, boolean transactionActive, boolean readOnly) { }

    private static class RaceObservation {
        private final String key;
        private final UUID nextId;
        private final ThreadLocal<String> lane = new ThreadLocal<>();
        private final CountDownLatch bothBeforeLock = new CountDownLatch(2);
        private final CountDownLatch firstCompleted = new CountDownLatch(1);
        private final Map<String, ProbeObservation> entityProbes = new ConcurrentHashMap<>();
        private final Map<String, LockObservation> beforeLocks = new ConcurrentHashMap<>();
        private final Map<String, ScalarProbeObservation> scalarProbes = new ConcurrentHashMap<>();
        private volatile VersionObservation afterFirstCommit;
        private RaceObservation(String key, UUID nextId) { this.key = key; this.nextId = nextId; }
        private boolean matches(Object[] args) {
            return lane.get() != null && args.length >= 3 && args[0] == BillingProductKind.PLAN
                    && key.equals(args[1]) && args[2] == EntitlementScopeKind.USER;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RaceObservationConfiguration {
        @Bean RaceObservationHook raceObservationHook() { return new RaceObservationHook(); }
    }

    /** 実Repo/実EntityManager/既存REQUIRED TXをcall-throughし、返値/Entityを変更しない。 */
    @Aspect
    @Order(Ordered.HIGHEST_PRECEDENCE + 100)
    static class RaceObservationHook {
        @PersistenceContext private EntityManager entityManager;
        private volatile RaceObservation current;

        @Around("execution(* com.mannschaft.app.billing.BillingPriceVersionRepository.findEffectiveCandidates(..))")
        Object observeProbe(ProceedingJoinPoint invocation) throws Throwable {
            Object result = invocation.proceed();
            RaceObservation state = current;
            Object[] args = invocation.getArgs();
            if (state != null && state.matches(args) && args.length >= 4
                    && args[3] instanceof java.util.Collection<?> statuses
                    && statuses.contains(BillingPriceVersionStatus.SCHEDULED) && result instanceof List<?> rows) {
                for (Object row : rows) {
                    if (row instanceof BillingPriceVersionEntity revision && state.nextId.equals(revision.getId())) {
                        boolean managed = entityManager.contains(revision);
                        String lockMode = managed ? entityManager.unwrap(org.hibernate.Session.class)
                                .getCurrentLockMode(revision).name() : "DETACHED";
                        state.entityProbes.put(state.lane.get(), new ProbeObservation(revision.getId(), revision.getLockVersion(),
                                managed, lockMode, TransactionSynchronizationManager.isActualTransactionActive(),
                                TransactionSynchronizationManager.isCurrentTransactionReadOnly()));
                    }
                }
            }
            return result;
        }

        @Around("execution(* com.mannschaft.app.billing.BillingPriceVersionRepository.countEffectiveCandidates(..))")
        Object observeScalarProbe(ProceedingJoinPoint invocation) throws Throwable {
            Object result = invocation.proceed();
            RaceObservation state = current;
            Object[] args = invocation.getArgs();
            if (state != null && state.matches(args) && args.length >= 4
                    && args[3] instanceof java.util.Collection<?> statuses
                    && statuses.contains(BillingPriceVersionStatus.SCHEDULED) && result instanceof Number count) {
                state.scalarProbes.put(state.lane.get(), new ScalarProbeObservation(count.longValue(),
                        entityManager.unwrap(org.hibernate.Session.class).getStatistics().getEntityCount(),
                        TransactionSynchronizationManager.isActualTransactionActive(),
                        TransactionSynchronizationManager.isCurrentTransactionReadOnly()));
            }
            return result;
        }

        @Around("execution(* com.mannschaft.app.billing.BillingPriceVersionRepository.findAllForUpdate(..))")
        Object beforeLock(ProceedingJoinPoint invocation) throws Throwable {
            RaceObservation state = current;
            if (state != null && state.matches(invocation.getArgs())) {
                String lane = state.lane.get();
                state.beforeLocks.put(lane, new LockObservation(TransactionSynchronizationManager.isActualTransactionActive(),
                        TransactionSynchronizationManager.isCurrentTransactionReadOnly()));
                state.bothBeforeLock.countDown();
                await(state.bothBeforeLock);
                if ("resolver".equals(lane)) await(state.firstCompleted);
            }
            return invocation.proceed();
        }
    }

    private Integer readPrice(String entry) {
        if ("catalog".equals(entry)) {
            return catalog.getCatalog().getPlans().stream().filter(plan -> planKey.equals(plan.getPlanKey()))
                    .findFirst().orElseThrow().getBaseMonthlyPriceJpy();
        }
        return resolver.resolveMonthlyPriceJpy(EntitlementScopeKind.USER, 1L, ContractKind.PLAN, planKey, null);
    }

    private void createPrices(Instant boundary) {
        transactions.executeWithoutResult(tx -> {
            planRepository.saveAndFlush(PlanEntity.builder().planKey(planKey).baseMonthlyPriceJpy(LEGACY_PRICE)
                    .displayNameKey("it1931").descriptionKey("it1931").sortOrder(0).enabled(true).build());
            currentId = savePrice(1L, BillingPriceVersionStatus.ACTIVE,
                    boundary.minusSeconds(86_400L), boundary, CURRENT_PRICE, 2_000L, 200L);
            nextId = savePrice(2L, BillingPriceVersionStatus.SCHEDULED, boundary, null, NEXT_PRICE, 3_300L, 330L);
        });
    }

    private UUID savePrice(long revisionNo, BillingPriceVersionStatus status, Instant from, Instant until,
                           long total, long excluding, long tax) {
        BillingPriceVersionEntity version = versions.saveAndFlush(BillingPriceVersionEntity.builder()
                .productKind(BillingProductKind.PLAN).productKey(planKey).scopeKind(EntitlementScopeKind.USER)
                .catalogRevision("1931-" + revisionNo).revisionNo(revisionNo).status(status)
                .effectiveFrom(from).effectiveUntil(until).provisionAttempts(0)
                .creationSource(BillingPriceCreationSource.SYSTEM_BACKFILL).build());
        bandIds.add(bands.saveAndFlush(BillingPriceBandVersionEntity.builder()
                .productKind(BillingProductKind.PLAN).productKey(planKey).scopeKind(EntitlementScopeKind.USER)
                .priceVersionId(version.getId()).bandNo(1).minMembers(1).currency("JPY")
                .stripePriceRef("price_it1931p_" + UUID.randomUUID()).inputAmount(total)
                .taxBehavior(BillingTaxBehavior.INCLUSIVE).taxCodeSnapshot("txcd_10000000").taxMasterSnapshot("{}")
                .amountExcludingTax(excluding).taxAmount(tax).amountIncludingTax(total).taxRateBasisPoints(1_000)
                .taxNameSnapshot("消費税").includedInPrice(true).effectiveFrom(from).effectiveUntil(until)
                .status(status).creationSource(BillingPriceCreationSource.SYSTEM_BACKFILL).build()).getId());
        return version.getId();
    }

    private void assertStates(BillingPriceVersionStatus current, BillingPriceVersionStatus next) {
        transactions.executeWithoutResult(tx -> {
            assertThat(versions.findById(currentId).orElseThrow().getStatus()).isEqualTo(current);
            assertThat(versions.findById(nextId).orElseThrow().getStatus()).isEqualTo(next);
            assertThat(bands.findByPriceVersionIdAndDeletedAtIsNullOrderByBandNoAsc(currentId))
                    .hasSize(1).allSatisfy(band -> assertThat(band.getStatus()).isEqualTo(current));
            assertThat(bands.findByPriceVersionIdAndDeletedAtIsNullOrderByBandNoAsc(nextId))
                    .hasSize(1).allSatisfy(band -> assertThat(band.getStatus()).isEqualTo(next));
        });
    }

    private void time(Instant at) {
        ((MutablePriceClock) clock).at.set(at);
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw new AssertionError("legacy並行読取の開始待ちがtimeoutしました");
        }
    }

    /** 実Clockを維持したまま時点だけを決定論的に進める。 */
    private static final class MutablePriceClock extends Clock {
        private final AtomicReference<Instant> at;
        private final ZoneId zone;

        private MutablePriceClock(Instant instant, ZoneId zone) {
            this(new AtomicReference<>(instant), zone);
        }

        private MutablePriceClock(AtomicReference<Instant> at, ZoneId zone) {
            this.at = at;
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId newZone) {
            return new MutablePriceClock(at, newZone);
        }

        @Override
        public Instant instant() {
            return at.get();
        }
    }
}
