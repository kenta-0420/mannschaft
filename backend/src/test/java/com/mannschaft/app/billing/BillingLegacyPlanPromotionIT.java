package com.mannschaft.app.billing;

import com.mannschaft.app.billing.api.BillingCatalogQueryService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
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
