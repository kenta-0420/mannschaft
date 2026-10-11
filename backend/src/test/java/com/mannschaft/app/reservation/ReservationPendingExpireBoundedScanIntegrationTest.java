package com.mannschaft.app.reservation;

import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.common.visibility.perf.SqlIntentCounter;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.notification.confirmable.support.ConfirmableFanoutFixture;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.service.NotificationDispatchService;
import com.mannschaft.app.reservation.entity.ReservationEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.repository.ReservationPendingExpireScanStateRepository;
import com.mannschaft.app.reservation.repository.ReservationPolicyRepository;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import com.mannschaft.app.reservation.service.ReservationPendingExpireBatchService;
import com.mannschaft.app.reservation.service.ReservationPendingExpireProgressService;
import com.mannschaft.app.reservation.service.ReservationPendingExpireService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.team.repository.TeamRepository;
import jakarta.persistence.EntityManager;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * CMP1730公開走査のRED候補。設定再宣言/Bean人工例外なし、実MySQL/実proxyを使う。
 * 本クラス単独・scheduler停止・他runnerなしで選択する。未実行。
 * raw4500はretry+fresh候補の返却行だけ。TZ/兄弟/slotの返却行は加算しない。
 * 最大4501の境界fixtureは1ケースへまとめ、他は契約境界に必要な最小件数とする。
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ReservationPendingExpireBoundedScanIntegrationTest extends AbstractMySqlIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-06-03T15:00:00Z");
    private static final LocalDateTime WALL = LocalDateTime.ofInstant(NOW, UserZoneLocalDateTimeParser.SERVER_ZONE);
    @Autowired private ReservationPendingExpireBatchService batch;
    @Autowired private ReservationPendingExpireService expire;
    @Autowired private ReservationPendingExpireProgressService progress;
    @Autowired private ReservationRepository reservations;
    @Autowired private ReservationSlotRepository slots;
    @Autowired private ReservationPolicyRepository policies;
    @Autowired private ReservationPendingExpireScanStateRepository states;
    @Autowired private TeamRepository teams;
    @Autowired private MembershipRepository memberships;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EntityManager em;
    @Autowired private JdbcTemplate jdbc;
    @Autowired @Qualifier("notification-dispatch-pool") private ThreadPoolTaskExecutor dispatch;
    @Autowired private NotificationDispatchService notificationDispatch;
    @Autowired private DataSource dataSource;

    private final List<Long> reservationIds = new ArrayList<>();
    private final List<Long> slotIds = new ArrayList<>();
    private Long teamId;
    private Long userId;
    private Long membershipId;
    private UUID policyId;
    private Runnable beforeFirstUnit;
    private Runnable afterScanBeforeZone;
    private ZoneId observedUnitZone;
    private boolean createdState;
    private UUID stateId;
    private String trigger;
    private String stateTrigger;
    private String userPrefix;
    private Object expireTarget;
    private Object progressTarget;
    private Clock originalExpireClock;
    private Clock originalProgressClock;
    private DefaultPointcutAdvisor candidateObserver;
    private DefaultPointcutAdvisor unitObserver;
    private DefaultPointcutAdvisor scanObserver;
    private DefaultPointcutAdvisor dispatchObserver;
    private final AtomicInteger ownedDispatchCalls = new AtomicInteger();
    private final AtomicInteger committedDispatchCalls = new AtomicInteger();
    private final AtomicReference<Throwable> dispatchObservationFailure = new AtomicReference<>();
    private Thread caller;
    private int rawCandidates;
    private int candidateCalls;
    private int unitAttempts;
    private boolean unknownCandidateReturn;
    private boolean unknownUnitArgument;
    private final List<Long> attemptedPrimaryIds = new ArrayList<>();
    private final List<int[]> unitSqlRanges = new ArrayList<>();
    private final List<int[]> candidateSqlRanges = new ArrayList<>();
    private List<String> measuredSql = List.of();
    private boolean insertAboveHighWater;
    private Long insertedHighId;
    private long observedHighWater;
    private Throwable fixtureFailure;
    private boolean workersStopped = true;

    @BeforeEach
    void 所有fixtureと実proxy観測を準備する() throws Exception {
        assertThat(states.count()).as("既stateを上書きしない").isZero();
        stateId = states.saveAndFlush(ReservationPendingExpireTestFixture.minimumValidState(NOW)).getId();
        createdState = true;
        userPrefix = "cmp1730-" + UUID.randomUUID();
        userId = ConfirmableFanoutFixture.insertUsers(transactionManager, em, 1, userPrefix).getFirst();
        teamId = teams.saveAndFlush(ReservationPendingExpireTestFixture.team(
                "c1730-" + UUID.randomUUID().toString().substring(0, 12), "America/New_York")).getId();
        membershipId = memberships.saveAndFlush(
                ReservationPendingExpireTestFixture.membership(userId, teamId, WALL.minusDays(1))).getId();
        policyId = policies.saveAndFlush(ReservationPendingExpireTestFixture.policy(teamId)).getId();
        expireTarget = AopTestUtils.getTargetObject(expire);
        progressTarget = AopTestUtils.getTargetObject(progress);
        originalExpireClock = (Clock) ReflectionTestUtils.getField(expireTarget, "clock");
        originalProgressClock = (Clock) ReflectionTestUtils.getField(progressTarget, "clock");
        ReflectionTestUtils.setField(expireTarget, "clock", Clock.fixed(NOW, ZoneOffset.UTC));
        ReflectionTestUtils.setField(progressTarget, "clock", Clock.fixed(NOW, ZoneOffset.UTC));
        caller = Thread.currentThread();
        candidateObserver = observer(List.of("findExpirablePendingPrimaryRows", "findPendingPrimaryCandidates",
                        "findPendingPrimaryCandidatesByIds"),
                invocation -> {
                    int start = SqlIntentCounter.totalCount();
                    Object result = invocation.proceed();
                    if (Thread.currentThread() == caller) {
                        candidateCalls++;
                        candidateSqlRanges.add(new int[]{start, SqlIntentCounter.totalCount()});
                        if (result instanceof List<?> rows) {
                            rawCandidates += rows.size();
                        } else {
                            unknownCandidateReturn = true;
                        }
                        // COUNTや余分なSELECTをRepo呼出1回へ折り畳まない。
                        if (SqlIntentCounter.totalCount() - start != 1) {
                            unknownCandidateReturn = true;
                        }
                    }
                    return result;
                });
        unitObserver = observer(List.of("expireUnit"), invocation -> {
            if (Thread.currentThread() != caller) {
                return invocation.proceed();
            }
            unitAttempts++;
            if (beforeFirstUnit != null) {
                var mutation = beforeFirstUnit;
                beforeFirstUnit = null;
                var fixtureTx = new TransactionTemplate(transactionManager);
                fixtureTx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                fixtureTx.executeWithoutResult(status -> mutation.run());
            }
            if (insertAboveHighWater) {
                insertAboveHighWater = false;
                observedHighWater = jdbc.queryForObject(
                        "SELECT cycle_high_water FROM reservation_pending_expire_scan_state WHERE singleton_key = 1", Long.class);
                insertHighFixture(); // 有限fixtureだけ。戻り値/例外/属性を変えない。
            }
            if (invocation.getArguments().length > 0
                    && invocation.getArguments()[0] instanceof ReservationPendingExpireService.PendingExpireUnit unit) {
                attemptedPrimaryIds.add(unit.primary().getId());
                observedUnitZone = unit.zone();
            } else {
                unknownUnitArgument = true;
            }
            int start = SqlIntentCounter.totalCount();
            try {
                return invocation.proceed();
            } finally {
                unitSqlRanges.add(new int[]{start, SqlIntentCounter.totalCount()});
            }
        });
        scanObserver = observer(List.of("findScanPlan"), invocation -> {
            Object result = invocation.proceed();
            if (Thread.currentThread() == caller && afterScanBeforeZone != null) {
                assertThat(org.springframework.transaction.support.TransactionSynchronizationManager
                        .isActualTransactionActive()).as("単位直前TZ取得は業務TXの外").isFalse();
                var mutation = afterScanBeforeZone;
                afterScanBeforeZone = null;
                var fixtureTx = new TransactionTemplate(transactionManager);
                fixtureTx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                fixtureTx.executeWithoutResult(status -> mutation.run());
            }
            return result;
        });
        ((Advised) reservations).addAdvisor(0, candidateObserver);
        ((Advised) expire).addAdvisor(unitObserver);
        ((Advised) expire).addAdvisor(scanObserver);
        dispatchObserver = observer(List.of("dispatch"), invocation -> {
            if (invocation.getArguments()[0] instanceof NotificationEntity notification
                    && userId.equals(notification.getUserId())
                    && "RESERVATION_PENDING_EXPIRED".equals(notification.getNotificationType())) {
                ownedDispatchCalls.incrementAndGet();
                // DataSourceUtilsを使わず、単位TXと独立した接続からcommit済み行だけを見る。
                try (var connection = dataSource.getConnection();
                     var statement = connection.prepareStatement("SELECT COUNT(*) FROM notifications n "
                             + "JOIN reservations r ON r.id = n.source_id "
                             + "WHERE n.id = ? AND n.user_id = ? AND r.status = 'CANCELLED'")) {
                    assertThat(connection.getAutoCommit()).isTrue();
                    statement.setLong(1, notification.getId());
                    statement.setLong(2, userId);
                    try (var result = statement.executeQuery()) {
                        assertThat(result.next()).isTrue();
                        assertThat(result.getLong(1)).as("外部dispatch時に通知と予約取消が別接続で可視").isEqualTo(1);
                    }
                    committedDispatchCalls.incrementAndGet();
                } catch (Exception | AssertionError failure) {
                    dispatchObservationFailure.compareAndSet(null, failure);
                }
            }
            return invocation.proceed();
        });
        ((Advised) notificationDispatch).addAdvisor(dispatchObserver);
    }

    @AfterEach
    void 観測と所有fixtureだけを片付ける() {
        try {
            assertThat(workersStopped).as("生存workerがあればfixture削除を拒否する").isTrue();
            // AVAILABLE開始なのでreopened eventは発生しない。dispatch投入は同期public呼出内で完了する。
            long submitted = dispatch.getThreadPoolExecutor().getTaskCount();
            await().atMost(Duration.ofSeconds(15)).until(() ->
                    dispatch.getThreadPoolExecutor().getCompletedTaskCount() >= submitted
                            && dispatch.getThreadPoolExecutor().getQueue().isEmpty()
                            && dispatch.getActiveCount() == 0);
            try {
                try {
                    if (trigger != null) {
                        jdbc.execute("DROP TRIGGER IF EXISTS " + trigger);
                    }
                } finally {
                    if (stateTrigger != null) {
                        jdbc.execute("DROP TRIGGER IF EXISTS " + stateTrigger);
                    }
                }
            } finally {
                // DROP失敗でも所有削除を試みる。既state空precondition失敗時は削除しない。
                if (userId != null) {
                    jdbc.update("DELETE FROM notifications WHERE user_id = ? AND notification_type = ?",
                            userId, "RESERVATION_PENDING_EXPIRED");
                }
                reservations.deleteAllByIdInBatch(reservationIds);
                slots.deleteAllByIdInBatch(slotIds);
                if (policyId != null) policies.deleteById(policyId);
                if (membershipId != null) memberships.deleteById(membershipId);
                if (teamId != null) teams.deleteById(teamId);
                if (userPrefix != null) ConfirmableFanoutFixture.deleteUsers(transactionManager, em, userPrefix);
                if (createdState) jdbc.update(
                        "DELETE FROM reservation_pending_expire_scan_state WHERE id = UNHEX(REPLACE(?, '-', ''))",
                        stateId.toString());
            }
        } finally {
            if (candidateObserver != null) ((Advised) reservations).removeAdvisor(candidateObserver);
            if (unitObserver != null) ((Advised) expire).removeAdvisor(unitObserver);
            if (scanObserver != null) ((Advised) expire).removeAdvisor(scanObserver);
            if (dispatchObserver != null) ((Advised) notificationDispatch).removeAdvisor(dispatchObserver);
            if (originalExpireClock != null) ReflectionTestUtils.setField(expireTarget, "clock", originalExpireClock);
            if (originalProgressClock != null) ReflectionTestUtils.setField(progressTarget, "clock", originalProgressClock);
        }
        assertThat(dispatchObservationFailure.get()).as("独立接続のcommit観測失敗").isNull();
    }

    @Test
    void 候補返却raw4500に代表groupを含め全抽出SELECT12で止まり翌回4501番目へ進む() {
        // Tokyo壁時計は6/4 00:00、NYは6/3 11:00。6/3 17:00終了はNY21:00Zで未来、Tokyoでは過去。
        var falseSlot = slot(LocalDate.of(2026, 6, 3), LocalTime.of(16, 0), LocalTime.of(17, 0), 4499);
        List<ReservationEntity> falseRows = saveRows(falseSlot.getId(), 4499, WALL.minusHours(1));
        var trueSlot = slot(LocalDate.of(2026, 7, 1), LocalTime.NOON, LocalTime.of(13, 0), 2);
        UUID group = UUID.randomUUID();
        var primary = save(trueSlot.getId(), WALL.minusHours(48), group, true);
        var sibling1 = save(slot(LocalDate.of(2026, 7, 2), LocalTime.NOON, LocalTime.of(13, 0), 1).getId(),
                WALL.minusHours(1), group, false);
        var sibling2 = save(slot(LocalDate.of(2026, 7, 3), LocalTime.NOON, LocalTime.of(13, 0), 1).getId(),
                WALL.minusHours(1), group, false);
        var next = save(trueSlot.getId(), WALL.minusHours(48), null, true);
        assertThat(run()).isEqualTo(3); // このphaseはunit0ではなくgroup1単位・予約3行。
        assertThat(rawCandidates).as("retry+fresh候補返却だけ").isEqualTo(4500);
        assertThat(candidateCalls).isEqualTo(9);
        assertThat(unitAttempts).isEqualTo(1);
        assertExtractionBudget(12);
        assertThat(status(primary.getId())).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(status(sibling1.getId())).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(status(sibling2.getId())).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(status(next.getId())).isEqualTo(ReservationStatus.PENDING);
        assertThat(states.findById(stateId).orElseThrow().getLastInspectedId()).isEqualTo(primary.getId());
        assertThat(reservations.findAllById(falseRows.stream().map(ReservationEntity::getId).toList()))
                .allMatch(row -> row.getStatus() == ReservationStatus.PENDING);
        assertThat(run()).isEqualTo(1);
        assertThat(status(next.getId())).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(states.findById(stateId).orElseThrow().getRetryPrimaryIds()).isEmpty();
    }

    @Test
    void 真候補501の500上限と遅延低IDの次周回を区別する() throws Exception {
        var lowSlot = slot(LocalDate.of(2026, 7, 2), LocalTime.NOON, LocalTime.of(13, 0), 1);
        var lowId = new AtomicReference<Long>();
        var allocated = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        workersStopped = false;
        var producer = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            var low = reservations.saveAndFlush(row(lowSlot.getId(), WALL.minusHours(48), null, true));
            lowId.set(low.getId());
            allocated.countDown();
            try {
                if (!release.await(60, TimeUnit.SECONDS)) throw new IllegalStateException("低ID fixtureの解放待ち時間切れ");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("低ID fixture中断", e);
            }
        }));
        try {
        assertThat(allocated.await(10, TimeUnit.SECONDS)).isTrue();
        reservationIds.add(lowId.get());
        var slot = slot(LocalDate.of(2026, 7, 1), LocalTime.NOON, LocalTime.of(13, 0), 501);
        var rows = saveRows(slot.getId(), 501, WALL.minusHours(48));
        assertThat(run()).isEqualTo(500);
        assertThat(unitAttempts).isEqualTo(500);
        assertThat(status(rows.get(500).getId())).isEqualTo(ReservationStatus.PENDING);
        assertThat(states.findById(stateId).orElseThrow().getLastInspectedId()).isEqualTo(rows.get(499).getId());
        assertExtractionBudget(-1);
        release.countDown();
        producer.get(10, TimeUnit.SECONDS); // lowIDはcursorより前へ遅延commitした。
        assertThat(run()).isEqualTo(1);
        assertThat(status(rows.get(500).getId())).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(status(lowId.get())).isEqualTo(ReservationStatus.PENDING);
        assertThat(states.findById(stateId).orElseThrow().getCycleHighWater()).isZero();
        assertThat(run()).isEqualTo(1);
        assertThat(status(lowId.get())).isEqualTo(ReservationStatus.CANCELLED);
        } finally {
            release.countDown();
            executor.shutdown();
            workersStopped = executor.awaitTermination(15, TimeUnit.SECONDS);
            if (!workersStopped) {
                executor.shutdownNow();
                workersStopped = executor.awaitTermination(10, TimeUnit.SECONDS);
            }
            assertThat(workersStopped).isTrue();
            if (lowId.get() != null && !reservationIds.contains(lowId.get())) reservationIds.add(lowId.get());
            producer.get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void highWater取得後の高IDは次周回だけ処理する() {
        var slot = slot(LocalDate.of(2026, 7, 1), LocalTime.NOON, LocalTime.of(13, 0), 2);
        var first = save(slot.getId(), WALL.minusHours(48), null, true);
        insertAboveHighWater = true;
        assertThat(run()).isEqualTo(1);
        assertThat(fixtureFailure).as("fixture失敗を契約REDへ転用しない").isNull();
        assertThat(insertedHighId).as("観測点へ到達し、新高IDのcommitが完了した").isNotNull();
        assertThat(observedHighWater).isEqualTo(first.getId());
        assertThat(status(insertedHighId)).isEqualTo(ReservationStatus.PENDING);
        assertThat(run()).isEqualTo(1);
        assertThat(status(insertedHighId)).isEqualTo(ReservationStatus.CANCELLED);
    }

    private void insertHighFixture() {
        var executor = Executors.newSingleThreadExecutor();
        var allocatedId = new AtomicReference<Long>();
        workersStopped = false;
        var future = executor.submit(() -> new TransactionTemplate(transactionManager).execute(tx -> {
            Long id = reservations.saveAndFlush(row(slotIds.getFirst(), WALL.minusHours(48), null, true)).getId();
            allocatedId.set(id); // timeout後commit/rollbackでも、割当IDをownedcleanupへ渡す。
            return id;
        }));
        try {
            insertedHighId = future.get(10, TimeUnit.SECONDS);
            reservationIds.add(insertedHighId);
        } catch (Exception e) {
            fixtureFailure = e; // advisorから人工例外を本体へthrowしない。
        } finally {
            executor.shutdown();
            try {
                workersStopped = executor.awaitTermination(10, TimeUnit.SECONDS);
                if (!workersStopped) {
                    executor.shutdownNow();
                    workersStopped = executor.awaitTermination(5, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fixtureFailure = e;
            }
            if (workersStopped && allocatedId.get() != null && !reservationIds.contains(allocatedId.get())) {
                reservationIds.add(allocatedId.get());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {499, 500})
    void 永久失敗retryの後は残りの試行予算だけfreshへ使う(int retries) {
        var slot = slot(LocalDate.of(2026, 7, 1), LocalTime.NOON, LocalTime.of(13, 0), retries + 1);
        var fresh = save(slot.getId(), WALL.minusHours(48), null, true);
        var old = saveRows(slot.getId(), retries, WALL.minusHours(48));
        String idsJson = old.stream().map(row -> row.getId().toString()).collect(Collectors.joining(",", "[", "]"));
        jdbc.update("UPDATE reservation_pending_expire_scan_state SET retry_primary_ids = ? WHERE singleton_key = 1", idsJson);
        trigger = "cmp1730_fail_" + UUID.randomUUID().toString().replace("-", "");
        long cutoff = old.getLast().getId();
        long firstRetry = old.getFirst().getId();
        // DDL/fixture commitは測定TXの外。試験ownedteamと返却ID範囲だけを拒否する。
        jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE UPDATE ON reservations FOR EACH ROW BEGIN "
                + "IF NEW.team_id = " + teamId + " AND NEW.id >= " + firstRetry + " AND NEW.id <= " + cutoff
                + " AND OLD.status = 'PENDING' AND NEW.status = 'CANCELLED' THEN "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'CMP1730 owned retry failure'; END IF; END");
        assertThat(run()).isEqualTo(500 - retries);
        assertThat(unitAttempts).isEqualTo(500);
        var expectedAttempts = new ArrayList<>(old.stream().map(ReservationEntity::getId).toList());
        if (retries == 499) expectedAttempts.add(fresh.getId());
        assertThat(attemptedPrimaryIds).as("ID昇順のfreshを先に試す旧実装を拒否する")
                .containsExactlyElementsOf(expectedAttempts);
        assertThat(states.findById(stateId).orElseThrow().getRetryPrimaryIds())
                .containsExactlyElementsOf(old.stream().map(ReservationEntity::getId).toList());
        assertThat(status(fresh.getId())).isEqualTo(retries == 500 ? ReservationStatus.PENDING : ReservationStatus.CANCELLED);
        assertThat(slots.findById(slot.getId()).orElseThrow().getBookedCount()).isEqualTo(retries == 500 ? 501 : 499);
        assertExtractionBudget(-1);
    }

    @Test
    void 抽出後に追加されたPENDING兄弟と最新枠も同じunitで失効する() {
        var group = UUID.randomUUID();
        var firstSlot = slot(LocalDate.of(2026, 7, 1), LocalTime.NOON, LocalTime.of(13, 0), 1);
        var secondSlot = slot(LocalDate.of(2026, 7, 2), LocalTime.NOON, LocalTime.of(13, 0), 1);
        var primary = save(firstSlot.getId(), WALL.minusHours(48), group, true);
        var added = new AtomicReference<ReservationEntity>();
        beforeFirstUnit = () -> added.set(save(secondSlot.getId(), WALL.minusHours(1), group, false));
        assertThat(run()).isEqualTo(2);
        assertThat(status(primary.getId())).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(status(added.get().getId())).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(slots.findById(firstSlot.getId()).orElseThrow().getBookedCount()).isZero();
        assertThat(slots.findById(secondSlot.getId()).orElseThrow().getBookedCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id = ? "
                + "AND notification_type = 'RESERVATION_PENDING_EXPIRED'", Long.class, userId)).isEqualTo(1);
        assertThat(states.findById(stateId).orElseThrow().getRetryPrimaryIds()).isEmpty();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(ownedDispatchCalls.get()).isEqualTo(1);
            assertThat(committedDispatchCalls.get()).isEqualTo(1);
            assertThat(dispatchObservationFailure.get()).isNull();
        });
        assertExtractionBudget(-1);
    }

    @Test
    void 抽出後のpolicy無効化は最新値で再判定して取消と通知をしない() {
        var slot = slot(LocalDate.of(2026, 7, 1), LocalTime.NOON, LocalTime.of(13, 0), 1);
        var primary = save(slot.getId(), WALL.minusHours(48), null, true);
        beforeFirstUnit = () -> jdbc.update("UPDATE reservation_policies SET pending_expire_hours = NULL WHERE team_id = ?", teamId);
        assertThat(run()).isZero();
        assertThat(status(primary.getId())).isEqualTo(ReservationStatus.PENDING);
        assertThat(slots.findById(slot.getId()).orElseThrow().getBookedCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id = ? "
                + "AND notification_type = 'RESERVATION_PENDING_EXPIRED'", Long.class, userId)).isZero();
        assertThat(states.findById(stateId).orElseThrow().getRetryPrimaryIds()).isEmpty();
        assertExtractionBudget(-1);
    }

    @Test
    void 抽出後の枠延期は最新終了期限で再判定して失効しない() {
        var slot = slot(LocalDate.of(2026, 6, 2), LocalTime.NOON, LocalTime.of(13, 0), 1);
        var primary = save(slot.getId(), WALL.minusHours(1), null, true);
        beforeFirstUnit = () -> jdbc.update("UPDATE reservation_slots SET slot_date = ?, end_date = ? WHERE id = ?",
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 1), slot.getId());
        assertThat(run()).isZero();
        assertThat(status(primary.getId())).isEqualTo(ReservationStatus.PENDING);
        assertThat(slots.findById(slot.getId()).orElseThrow().getBookedCount()).isEqualTo(1);
        assertThat(states.findById(stateId).orElseThrow().getRetryPrimaryIds()).isEmpty();
        assertExtractionBudget(-1);
    }

    @Test
    void 抽出後のbookedAt延期は最新値で再判定して失効と通知をしない() {
        var slot = slot(LocalDate.of(2026, 7, 1), LocalTime.NOON, LocalTime.of(13, 0), 1);
        var primary = save(slot.getId(), WALL.minusHours(48), null, true);
        beforeFirstUnit = () -> jdbc.update("UPDATE reservations SET booked_at = ? WHERE id = ?",
                WALL.minusHours(1), primary.getId());
        assertThat(run()).isZero();
        assertThat(unitAttempts).isEqualTo(1);
        assertThat(status(primary.getId())).isEqualTo(ReservationStatus.PENDING);
        assertThat(slots.findById(slot.getId()).orElseThrow().getBookedCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id = ? "
                + "AND notification_type = 'RESERVATION_PENDING_EXPIRED'", Long.class, userId)).isZero();
        assertThat(states.findById(stateId).orElseThrow().getRetryPrimaryIds()).isEmpty();
        assertExtractionBudget(-1);
    }

    @Test
    void 単位直前TZ取得前の変更は最新ゾーンで再判定する() {
        jdbc.update("UPDATE teams SET timezone = ? WHERE id = ?", "Asia/Tokyo", teamId);
        var slot = slot(LocalDate.of(2026, 6, 3), LocalTime.of(22, 30), LocalTime.of(23, 30), 1);
        var primary = save(slot.getId(), WALL.minusHours(1), null, true);
        // 抽出Tokyoでは14:30Zで過去。取得前にNYへcommitすると翌日03:30Zで未来になる。
        afterScanBeforeZone = () -> jdbc.update("UPDATE teams SET timezone = ? WHERE id = ?",
                "America/New_York", teamId);
        assertThat(run()).isZero();
        assertThat(unitAttempts).isEqualTo(1);
        assertThat(observedUnitZone).isEqualTo(ZoneId.of("America/New_York"));
        assertThat(status(primary.getId())).isEqualTo(ReservationStatus.PENDING);
        assertThat(slots.findById(slot.getId()).orElseThrow().getBookedCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id = ? "
                + "AND notification_type = 'RESERVATION_PENDING_EXPIRED'", Long.class, userId)).isZero();
        assertThat(states.findById(stateId).orElseThrow().getRetryPrimaryIds()).isEmpty();
        assertExtractionBudget(-1);
    }

    @Test
    void 単位直前TZ取得後の変更は取得済みsnapshotを維持する() {
        jdbc.update("UPDATE teams SET timezone = ? WHERE id = ?", "Asia/Tokyo", teamId);
        var slot = slot(LocalDate.of(2026, 6, 3), LocalTime.of(22, 30), LocalTime.of(23, 30), 1);
        var primary = save(slot.getId(), WALL.minusHours(1), null, true);
        // resolveZone後・expireUnit本体前にNYへcommitしても、取得済みTokyoを使う。
        beforeFirstUnit = () -> jdbc.update("UPDATE teams SET timezone = ? WHERE id = ?",
                "America/New_York", teamId);
        assertThat(run()).isEqualTo(1);
        assertThat(unitAttempts).isEqualTo(1);
        assertThat(observedUnitZone).isEqualTo(ZoneId.of("Asia/Tokyo"));
        assertThat(jdbc.queryForObject("SELECT timezone FROM teams WHERE id = ?", String.class, teamId))
                .isEqualTo("America/New_York");
        assertThat(status(primary.getId())).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(slots.findById(slot.getId()).orElseThrow().getBookedCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id = ? "
                + "AND notification_type = 'RESERVATION_PENDING_EXPIRED'", Long.class, userId)).isEqualTo(1);
        assertThat(states.findById(stateId).orElseThrow().getRetryPrimaryIds()).isEmpty();
        assertExtractionBudget(-1);
    }

    @Test
    void 必要枠の欠損は取消通知をrollbackしretryを残す() {
        var slot = slot(LocalDate.of(2026, 7, 1), LocalTime.NOON, LocalTime.of(13, 0), 1);
        var primary = save(slot.getId(), WALL.minusHours(48), null, true);
        beforeFirstUnit = () -> jdbc.update("DELETE FROM reservation_slots WHERE id = ?", slot.getId());
        assertThat(run()).isZero();
        assertThat(unitAttempts).isEqualTo(1);
        assertThat(status(primary.getId())).isEqualTo(ReservationStatus.PENDING);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id = ? "
                + "AND notification_type = 'RESERVATION_PENDING_EXPIRED'", Long.class, userId)).isZero();
        assertThat(states.findById(stateId).orElseThrow().getRetryPrimaryIds()).containsExactly(primary.getId());
        assertExtractionBudget(-1);
    }

    @Test
    void primary消滅は欠損枠失敗と区別してterminalSkipする() {
        var slot = slot(LocalDate.of(2026, 7, 1), LocalTime.NOON, LocalTime.of(13, 0), 1);
        var primary = save(slot.getId(), WALL.minusHours(48), null, true);
        beforeFirstUnit = () -> jdbc.update("DELETE FROM reservations WHERE id = ?", primary.getId());
        assertThat(run()).isZero();
        assertThat(reservations.findById(primary.getId())).isEmpty();
        assertThat(slots.findById(slot.getId()).orElseThrow().getBookedCount()).isEqualTo(1);
        assertThat(states.findById(stateId).orElseThrow().getRetryPrimaryIds()).isEmpty();
        assertExtractionBudget(-1);
    }

    @Test
    void 単位完了の実DB失敗は通知と取消をrollbackし外部配信を投入しない() {
        var slot = slot(LocalDate.of(2026, 7, 1), LocalTime.NOON, LocalTime.of(13, 0), 1);
        var primary = save(slot.getId(), WALL.minusHours(48), null, true);
        stateTrigger = "cmp1730_complete_" + UUID.randomUUID().toString().replace("-", "");
        // enqueue済みretryを取り除く単位完了だけ拒否。失敗checkpointはretryを保持するため通る。
        jdbc.execute("CREATE TRIGGER " + stateTrigger
                + " BEFORE UPDATE ON reservation_pending_expire_scan_state FOR EACH ROW BEGIN "
                + "IF NEW.singleton_key = 1 AND JSON_LENGTH(OLD.retry_primary_ids) > 0 "
                + "AND JSON_LENGTH(NEW.retry_primary_ids) = 0 THEN "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'CMP1730 owned unit completion failure'; END IF; END");
        long submittedBefore = dispatch.getThreadPoolExecutor().getTaskCount();
        assertThat(run()).isZero();
        assertThat(unitAttempts).isEqualTo(1);
        assertThat(status(primary.getId())).isEqualTo(ReservationStatus.PENDING);
        assertThat(slots.findById(slot.getId()).orElseThrow().getBookedCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id = ? "
                + "AND notification_type = 'RESERVATION_PENDING_EXPIRED'", Long.class, userId)).isZero();
        assertThat(states.findById(stateId).orElseThrow().getRetryPrimaryIds()).containsExactly(primary.getId());
        assertThat(dispatch.getThreadPoolExecutor().getTaskCount()).as("rollback時にpoolへ投入しない")
                .isEqualTo(submittedBefore);
        assertThat(ownedDispatchCalls.get()).isZero();
        assertThat(committedDispatchCalls.get()).isZero();
        assertThat(dispatchObservationFailure.get()).isNull();
        assertExtractionBudget(-1);
    }

    @Test
    void 失敗unit後のcheckpoint実DB失敗は後続を停止しretryを消さない() {
        var slot = slot(LocalDate.of(2026, 7, 1), LocalTime.NOON, LocalTime.of(13, 0), 2);
        var first = save(slot.getId(), WALL.minusHours(48), null, true);
        var next = save(slot.getId(), WALL.minusHours(48), null, true);
        trigger = "cmp1730_fail_" + UUID.randomUUID().toString().replace("-", "");
        stateTrigger = "cmp1730_checkpoint_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE UPDATE ON reservations FOR EACH ROW BEGIN "
                + "IF NEW.id = " + first.getId() + " AND OLD.status = 'PENDING' AND NEW.status = 'CANCELLED' THEN "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'CMP1730 owned unit failure'; END IF; END");
        jdbc.execute("CREATE TRIGGER " + stateTrigger
                + " BEFORE UPDATE ON reservation_pending_expire_scan_state FOR EACH ROW BEGIN "
                + "IF NEW.id = UNHEX(REPLACE('" + stateId + "', '-', ''))"
                + " AND NEW.last_inspected_id > OLD.last_inspected_id THEN "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'CMP1730 owned checkpoint failure'; END IF; END");
        assertThatThrownBy(this::run).hasStackTraceContaining("CMP1730 owned checkpoint failure");
        assertThat(unitAttempts).isEqualTo(1);
        assertThat(status(first.getId())).isEqualTo(ReservationStatus.PENDING);
        assertThat(status(next.getId())).isEqualTo(ReservationStatus.PENDING);
        assertThat(slots.findById(slot.getId()).orElseThrow().getBookedCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id = ? "
                + "AND notification_type = 'RESERVATION_PENDING_EXPIRED'", Long.class, userId)).isZero();
        var state = states.findById(stateId).orElseThrow();
        assertThat(state.getRetryPrimaryIds()).containsExactly(first.getId());
        assertThat(state.getLastInspectedId()).isZero();
        assertThat(state.getCycleHighWater()).isEqualTo(next.getId());
        assertExtractionBudget(-1);
    }

    private Integer run() {
        long priorSubmitted = dispatch.getThreadPoolExecutor().getTaskCount();
        await().atMost(Duration.ofSeconds(15)).until(() ->
                dispatch.getThreadPoolExecutor().getCompletedTaskCount() >= priorSubmitted
                        && dispatch.getThreadPoolExecutor().getQueue().isEmpty() && dispatch.getActiveCount() == 0);
        rawCandidates = 0;
        candidateCalls = 0;
        unitAttempts = 0;
        unknownCandidateReturn = false;
        unknownUnitArgument = false;
        attemptedPrimaryIds.clear();
        unitSqlRanges.clear();
        candidateSqlRanges.clear();
        SqlIntentCounter.reset();
        Throwable businessFailure = null;
        try {
            return batch.expirePendingReservations();
        } catch (RuntimeException | Error failure) {
            businessFailure = failure;
            throw failure;
        } finally {
            try {
                measuredSql = SqlIntentCounter.capturedSqls(); // DBassert/cleanupのSQLを測定へ混ぜない。
                saveMeasuredSql();
            } catch (RuntimeException | Error recordingFailure) {
                if (businessFailure != null) {
                    businessFailure.addSuppressed(recordingFailure);
                } else {
                    throw recordingFailure;
                }
            }
        }
    }

    private void saveMeasuredSql() {
        try {
            Path directory = Path.of("build", "reports", "cmp1730-bounded-scan");
            Files.createDirectories(directory);
            Path raw = directory.resolve(UUID.randomUUID() + ".sql.raw");
            Files.writeString(raw, String.join("\n", measuredSql), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            Path receipt = Path.of(raw + ".receipt.txt");
            String ranges = unitSqlRanges.stream().map(range -> range[0] + ":" + range[1])
                    .collect(Collectors.joining(","));
            Files.writeString(receipt, "retry+fresh候補返却raw=" + rawCandidates + "\n候補呼出="
                    + candidateCalls + "\nunit試行=" + unitAttempts + "\nHibernate全SQL="
                    + measuredSql.size() + "\nunit区間=" + ranges + "\n候補未知=" + unknownCandidateReturn
                    + "\nunit引数未知=" + unknownUnitArgument + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            System.out.println("CMP1730 SQL原本: " + raw + " / " + receipt);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("CMP1730観測原本の保存失敗（契約REDではない）", e);
        }
    }

    private void assertExtractionBudget(int expected) {
        assertThat(unknownCandidateReturn).as("raw/COUNT観測欠落を0本合格にしない").isFalse();
        assertThat(unknownUnitArgument).as("本体unit観測欠落を0試行合格にしない").isFalse();
        assertThat(candidateCalls).isBetween(1, 9);
        assertThat(rawCandidates).isBetween(1, 4500);
        int bulk = 0;
        var unknownReservationSelects = new ArrayList<String>();
        for (int i = 0; i < measuredSql.size(); i++) {
            final int index = i;
            if (unitSqlRanges.stream().anyMatch(range -> index >= range[0] && index < range[1])) continue;
            if (candidateSqlRanges.stream().anyMatch(range -> index >= range[0] && index < range[1])) continue;
            String sql = measuredSql.get(i).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            if (sql.matches("(?s).*\\bgroup_id\\s+in\\s*\\(.*")
                    || (sql.matches("(?s).*\\bid\\s+in\\s*\\(.*")
                    && (sql.contains(" from teams ") || sql.contains(" from reservation_slots ")))) {
                bulk++;
            } else if (sql.startsWith("select ")
                    && (sql.contains(" from reservation_slots ") || sql.contains(" from reservations "))
                    && !sql.contains("max(")) {
                unknownReservationSelects.add(sql);
            }
        }
        assertThat(unknownReservationSelects).as("分類できない予約読取を抽出12本から黙って除外しない").isEmpty();
        assertThat(candidateCalls + bulk).isLessThanOrEqualTo(12);
        if (expected >= 0) assertThat(candidateCalls + bulk).isEqualTo(expected);
        // 原文とunit区間を原本化してROOTで分類する。Hibernate以外の全JDBCを見たとは呼ばない。
    }

    private static DefaultPointcutAdvisor observer(List<String> names, MethodInterceptor interceptor) {
        return new DefaultPointcutAdvisor(new StaticMethodMatcherPointcut() {
            @Override public boolean matches(Method method, Class<?> targetClass) {
                return names.contains(method.getName());
            }
        }, interceptor);
    }

    private ReservationSlotEntity slot(LocalDate date, LocalTime start, LocalTime end, int booked) {
        var saved = slots.saveAndFlush(ReservationPendingExpireTestFixture.slot(teamId, date, start, end, booked));
        slotIds.add(saved.getId());
        return saved;
    }

    private ReservationEntity row(Long slotId, LocalDateTime booked, UUID group, boolean primary) {
        return ReservationPendingExpireTestFixture.pending(teamId, userId, slotId, booked, group, primary);
    }

    private ReservationEntity save(Long slotId, LocalDateTime booked, UUID group, boolean primary) {
        var saved = reservations.saveAndFlush(row(slotId, booked, group, primary));
        reservationIds.add(saved.getId());
        return saved;
    }

    private List<ReservationEntity> saveRows(Long slotId, int count, LocalDateTime booked) {
        var saved = reservations.saveAllAndFlush(IntStream.range(0, count)
                .mapToObj(i -> row(slotId, booked, null, true)).toList());
        reservationIds.addAll(saved.stream().map(ReservationEntity::getId).toList());
        return saved;
    }

    private ReservationStatus status(Long id) {
        return reservations.findById(id).orElseThrow().getStatus();
    }
}
