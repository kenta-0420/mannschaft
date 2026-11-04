package com.mannschaft.app.shiftbudget;

import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.shift.ShiftScheduleStatus;
import com.mannschaft.app.shift.event.ShiftArchivedEvent;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import com.mannschaft.app.shiftbudget.batch.ShiftBudgetConsumptionReconcileBatchService;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetConsumptionRepository;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetConsumptionService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.TeamOrgFixtureHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.awaitility.core.ConditionTimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * CMP-261021-0717: 整合バッチとAFTER_COMMIT取消リスナーの競合を実MySQLで固定する。
 *
 * <p>外側のTransactionalは付けない。fixtureを明示commitし、第三の接続で割当を保持する。
 * 異なる2つの取消TXが対象割当/消化PKを待つことをperformance_schemaで観測してから解放する。
 * 開始時刻やsleepに依存せず、両経路が取消前のPLANNEDを読んだ競合を再現する。</p>
 * <p>別シフト分30000円を残すので、二重減算後の8400円も非負となる。
 * CHECK違反によるrollbackで二重減算が隠れる検体にはしない。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@TestPropertySource(properties = "feature.shift-budget.enabled=true")
@DisplayName("0717 消化取消の並行冪等性・原子性（実DB）")
class ShiftBudgetConsumptionCancelRaceIT extends AbstractMySqlIntegrationTest {
    private static final BigDecimal FIRST = new BigDecimal("9600");
    private static final BigDecimal SECOND = new BigDecimal("12000");
    private static final BigDecimal OTHER_SHIFT = new BigDecimal("30000");
    private static final BigDecimal TOTAL = FIRST.add(SECOND).add(OTHER_SHIFT);
    private static final Duration LIMIT = Duration.ofSeconds(30);

    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private DomainEventPublisher eventPublisher;
    @Autowired private ShiftBudgetConsumptionReconcileBatchService batch;
    @Autowired private ShiftBudgetConsumptionService consumptionService;
    @Autowired private ShiftScheduleRepository schedules;
    @Autowired private ShiftSlotRepository slots;
    @Autowired private ShiftBudgetAllocationRepository allocations;
    @Autowired private ShiftBudgetConsumptionRepository consumptions;
    @Autowired @Qualifier("event-pool") private ThreadPoolTaskExecutor eventExecutor;
    @PersistenceContext private EntityManager em;

    private Long orgId;
    private Long teamId;
    private Fixture fixture;

    private record Fixture(Long shiftId, Long allocationId, List<Long> targetIds, Long otherId) { }

    @BeforeEach
    void setUp() {
        String nonce = UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        fixture = transactionTemplate.execute(tx -> {
            orgId = TeamOrgFixtureHelper.insertOrganization(em, "0717組織", "o-" + nonce);
            teamId = TeamOrgFixtureHelper.insertTeam(em, "0717チーム", "t-" + nonce);
            Long shiftId = schedules.save(ShiftBudgetConsumptionCancelRaceTestFixture
                    .schedule(teamId, ShiftScheduleStatus.ARCHIVED)).getId();
            Long otherShift = schedules.save(ShiftBudgetConsumptionCancelRaceTestFixture
                    .schedule(teamId, ShiftScheduleStatus.PUBLISHED)).getId();
            Long allocationId = allocations.save(ShiftBudgetConsumptionCancelRaceTestFixture
                    .allocation(orgId, teamId, TOTAL)).getId();
            Long first = saveConsumption(allocationId, shiftId, FIRST, ShiftBudgetConsumptionStatus.PLANNED);
            Long second = saveConsumption(allocationId, shiftId, SECOND, ShiftBudgetConsumptionStatus.PLANNED);
            Long other = saveConsumption(allocationId, otherShift, OTHER_SHIFT, ShiftBudgetConsumptionStatus.PLANNED);
            return new Fixture(shiftId, allocationId, List.of(first, second), other);
        });
    }

    @AfterEach
    void cleanup() {
        awaitEventsFinished();
        // テスト自身が採番した組織・チームの行だけを撤去する。
        jdbc.update("DELETE FROM audit_logs WHERE organization_id = ?", orgId);
        jdbc.update("DELETE FROM shift_budget_failed_events WHERE organization_id = ?", orgId);
        jdbc.update("DELETE FROM shift_budget_consumptions WHERE allocation_id IN "
                + "(SELECT id FROM shift_budget_allocations WHERE organization_id = ?)", orgId);
        jdbc.update("DELETE FROM shift_budget_allocations WHERE organization_id = ?", orgId);
        jdbc.update("DELETE FROM shift_slots WHERE schedule_id IN (SELECT id FROM shift_schedules WHERE team_id = ?)", teamId);
        jdbc.update("DELETE FROM shift_schedules WHERE team_id = ?", teamId);
        jdbc.update("DELETE FROM teams WHERE id = ?", teamId);
        jdbc.update("DELETE FROM organizations WHERE id = ?", orgId);
    }

    @Test
    @DisplayName("AC01/12 batchとarchive listenerが同じ2消化を読んでも各1回だけ減算する")
    void 整合バッチと取消リスナーが競合しても二重減算しない() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Integer> result;
        WaitSnapshot observed;
        try (Connection observer = observer(); Connection blocker = dataSource.getConnection()) {
            // P_S参照権限と実接続を先に検査し、環境不備と業務assertionのredを区別する。
            try (var query = observer.createStatement(); var rows = query.executeQuery(
                    "SELECT COUNT(*) FROM performance_schema.data_lock_waits")) {
                assertThat(rows.next()).isTrue();
            }
            blocker.setAutoCommit(false);
            long blockerConnectionId;
            try (var query = blocker.createStatement(); var rows = query.executeQuery("SELECT CONNECTION_ID()")) {
                assertThat(rows.next()).isTrue();
                blockerConnectionId = rows.getLong(1);
            }
            try {
                try (var lock = blocker.prepareStatement("SELECT id FROM shift_budget_allocations WHERE id = ? FOR UPDATE")) {
                    lock.setLong(1, fixture.allocationId());
                    try (var rows = lock.executeQuery()) { assertThat(rows.next()).isTrue(); }
                }
                result = executor.submit(batch::reconcileOrphanConsumptions);
                awaitLockWait(observer, "shift_budget_allocations", fixture.allocationId());
                assertThat(result.isDone()).isFalse();
                transactionTemplate.executeWithoutResult(tx -> eventPublisher.publish(
                        new ShiftArchivedEvent(fixture.shiftId(), teamId, null)));
                observed = awaitBothCancellationWaits(observer, blockerConnectionId);
            } finally {
                blocker.rollback();
            }
            assertThat(result.get(60, TimeUnit.SECONDS)).isGreaterThanOrEqualTo(2);
        } finally {
            executor.shutdown();
            assertThat(executor.awaitTermination(90, TimeUnit.SECONDS)).isTrue();
            awaitEventsFinished();
        }

        assertThat(amount()).as("別シフト30000円を残し、二重減算8400円を許さない: %s", observed)
                .isEqualByComparingTo(OTHER_SHIFT);
        assertThat(targetStatuses()).containsExactly("CANCELLED", "CANCELLED");
        assertThat(status(fixture.otherId())).isEqualTo("PLANNED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shift_budget_failed_events "
                + "WHERE organization_id = ? AND event_type = 'CONSUMPTION_CANCEL' AND source_id = ?",
                Integer.class, orgId, fixture.shiftId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE organization_id = ? "
                + "AND event_type = 'SHIFT_BUDGET_CONSUMPTION_CANCELLED' "
                + "AND JSON_UNQUOTE(JSON_EXTRACT(metadata, '$.shift_schedule_id')) = ?",
                Integer.class, orgId, fixture.shiftId().toString())).isEqualTo(1);
    }

    @Test
    @DisplayName("AC08 逐次再実行は0件、CONFIRMED・CANCELLED・削除済み・対象0件は不変")
    void 逐次重複取消と取消対象外は無作用() {
        Long zero = transactionTemplate.execute(tx -> saveConsumption(fixture.allocationId(),
                fixture.shiftId(), BigDecimal.ZERO, ShiftBudgetConsumptionStatus.PLANNED));
        List<Long> unchanged = transactionTemplate.execute(tx -> List.of(
                saveConsumption(fixture.allocationId(), fixture.shiftId(), BigDecimal.ZERO, ShiftBudgetConsumptionStatus.CONFIRMED),
                saveConsumption(fixture.allocationId(), fixture.shiftId(), BigDecimal.ZERO, ShiftBudgetConsumptionStatus.CANCELLED)));
        Long deleted = transactionTemplate.execute(tx -> {
            Long slot = slots.save(ShiftBudgetConsumptionCancelRaceTestFixture.slot(fixture.shiftId())).getId();
            return consumptions.save(ShiftBudgetConsumptionCancelRaceTestFixture.consumption(
                    fixture.allocationId(), fixture.shiftId(), slot, BigDecimal.ZERO,
                    ShiftBudgetConsumptionStatus.PLANNED).toBuilder().deletedAt(java.time.LocalDateTime.now()).build()).getId();
        });

        assertThat(consumptionService.cancelAllForShift(fixture.shiftId())).isEqualTo(3);
        assertThat(consumptionService.cancelAllForShift(fixture.shiftId())).isZero();
        assertThat(consumptionService.cancelAllForShift(Long.MIN_VALUE)).isZero();

        assertThat(amount()).isEqualByComparingTo(OTHER_SHIFT);
        assertThat(status(zero)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT cancel_reason FROM shift_budget_consumptions WHERE id = ?",
                String.class, zero)).isEqualTo("SHIFT_DELETED");
        assertThat(jdbc.queryForObject("SELECT amount FROM shift_budget_consumptions WHERE id = ?",
                BigDecimal.class, zero)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(status(unchanged.getFirst())).isEqualTo("CONFIRMED");
        assertThat(status(unchanged.getLast())).isEqualTo("CANCELLED");
        assertThat(status(deleted)).isEqualTo("PLANNED");
        assertThat(jdbc.queryForList("SELECT cancel_reason FROM shift_budget_consumptions WHERE shift_id = ? "
                + "AND deleted_at IS NULL AND cancel_reason IS NOT NULL", String.class, fixture.shiftId()))
                .containsExactly("SHIFT_DELETED", "SHIFT_DELETED", "SHIFT_DELETED");
    }

    @Test
    @DisplayName("AC09/12 2件目の実SQL減算が失敗したら、先行分を含め消化と割当が同時rollbackする")
    void 減算途中失敗時は消化更新も全件巻き戻る() {
        String trigger = "cmp0717_cancel_" + fixture.allocationId();
        // 共通Hibernate schemaにはFlywayのCHECKが無い。対象IDの2件目減算だけを実DBで拒否する。
        jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE UPDATE ON shift_budget_allocations FOR EACH ROW "
                + "BEGIN IF OLD.id = " + fixture.allocationId() + " AND NEW.consumed_amount = 30000 "
                + "THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '0717 decrement rejected'; END IF; END");
        try {
            assertThatThrownBy(() -> consumptionService.cancelAllForShift(fixture.shiftId()))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class)
                    .hasStackTraceContaining("0717 decrement rejected");
            assertThat(targetStatuses()).containsExactly("PLANNED", "PLANNED");
            assertThat(amount()).isEqualByComparingTo(TOTAL);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shift_budget_consumptions WHERE shift_id = ? "
                    + "AND (cancelled_at IS NOT NULL OR cancel_reason IS NOT NULL)", Integer.class,
                    fixture.shiftId())).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER " + trigger);
        }
        assertThat(consumptionService.cancelAllForShift(fixture.shiftId())).isEqualTo(2);
        assertThat(amount()).isEqualByComparingTo(OTHER_SHIFT);
    }

    private Long saveConsumption(Long allocationId, Long shiftId, BigDecimal value, ShiftBudgetConsumptionStatus state) {
        Long slotId = slots.save(ShiftBudgetConsumptionCancelRaceTestFixture.slot(shiftId)).getId();
        return consumptions.save(ShiftBudgetConsumptionCancelRaceTestFixture
                .consumption(allocationId, shiftId, slotId, value, state)).getId();
    }

    private Connection observer() throws SQLException {
        // 資格情報は専用Testcontainer fixtureのみ。共有devDBへ接続しない。
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
    }

    private void awaitLockWait(Connection observer, String table, Long id) {
        await().atMost(LIMIT).pollInterval(Duration.ofMillis(50)).untilAsserted(() -> {
            try (var query = observer.prepareStatement("""
                    SELECT COUNT(*) FROM performance_schema.data_lock_waits w
                    JOIN performance_schema.data_locks l ON l.ENGINE = w.ENGINE
                     AND l.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID
                    WHERE l.OBJECT_SCHEMA = ? AND l.OBJECT_NAME = ? AND l.INDEX_NAME = 'PRIMARY'
                     AND l.LOCK_TYPE = 'RECORD' AND l.LOCK_DATA = ?
                    """)) {
                query.setString(1, MYSQL.getDatabaseName());
                query.setString(2, table);
                query.setString(3, id.toString());
                try (var rows = query.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong(1)).as("対象行の実DB lock wait: %s/%s", table, id).isPositive();
                }
            }
        });
    }

    /** 対象PKで待つ2つの取消TXを、待ち先のテーブルを固定せず観測する。 */
    private WaitSnapshot awaitBothCancellationWaits(Connection observer, long blockerConnectionId) {
        try {
            await().atMost(LIMIT).pollInterval(Duration.ofMillis(50)).untilAsserted(() -> {
                WaitSnapshot snapshot = cancellationWaits(observer, blockerConnectionId);
                assertThat(snapshot.waitingTransactions())
                        .as("対象PKで待つ異なる取消TX（blocker除外）: %s", snapshot.locks())
                        .isGreaterThanOrEqualTo(2);
            });
            return cancellationWaits(observer, blockerConnectionId);
        } catch (ConditionTimeoutException timeout) {
            WaitSnapshot snapshot;
            try {
                snapshot = cancellationWaits(observer, blockerConnectionId);
            } catch (SQLException failure) {
                throw new AssertionError("取消待機の診断SELECTが失敗", failure);
            }
            Integer failures = jdbc.queryForObject("SELECT COUNT(*) FROM shift_budget_failed_events "
                    + "WHERE organization_id = ? AND event_type = 'CONSUMPTION_CANCEL' AND source_id = ?",
                    Integer.class, orgId, fixture.shiftId());
            throw new AssertionError("取消待機の前提未成立: blocker_connection=" + blockerConnectionId
                    + ", snapshot=" + snapshot + ", cancel_failed_events=" + failures, timeout);
        } catch (SQLException failure) {
            throw new AssertionError("取消待機の診断SELECTが失敗", failure);
        }
    }

    private record WaitSnapshot(int waitingTransactions, List<String> locks) { }

    /** 当fixtureの割当PK/消化2PKのPRIMARY RECORD待機だけを取り出す。 */
    private WaitSnapshot cancellationWaits(Connection observer, long blockerConnectionId) throws SQLException {
        try (var query = observer.prepareStatement("""
                SELECT w.REQUESTING_ENGINE_TRANSACTION_ID AS requesting_tx,
                       w.BLOCKING_ENGINE_TRANSACTION_ID AS blocking_tx,
                       request_thread.PROCESSLIST_ID AS requesting_connection,
                       l.OBJECT_NAME, l.INDEX_NAME, l.LOCK_DATA, l.LOCK_MODE
                FROM performance_schema.data_lock_waits w
                JOIN performance_schema.data_locks l ON l.ENGINE = w.ENGINE
                 AND l.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID
                JOIN performance_schema.threads request_thread ON request_thread.THREAD_ID = w.REQUESTING_THREAD_ID
                WHERE l.OBJECT_SCHEMA = ? AND l.INDEX_NAME = 'PRIMARY' AND l.LOCK_TYPE = 'RECORD'
                 AND request_thread.PROCESSLIST_ID <> ?
                 AND ((l.OBJECT_NAME = 'shift_budget_allocations' AND l.LOCK_DATA = ?)
                  OR (l.OBJECT_NAME = 'shift_budget_consumptions' AND l.LOCK_DATA IN (?, ?)))
                """)) {
            query.setString(1, MYSQL.getDatabaseName());
            query.setLong(2, blockerConnectionId);
            query.setString(3, fixture.allocationId().toString());
            query.setString(4, fixture.targetIds().getFirst().toString());
            query.setString(5, fixture.targetIds().getLast().toString());
            var transactions = new HashSet<String>();
            var locks = new java.util.ArrayList<String>();
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    String request = rows.getString("requesting_tx");
                    transactions.add(request);
                    locks.add("tx=" + request + "/connection=" + rows.getLong("requesting_connection")
                            + " -> blocking_tx=" + rows.getString("blocking_tx") + " "
                            + rows.getString("OBJECT_NAME") + "/" + rows.getString("INDEX_NAME") + "/"
                            + rows.getString("LOCK_DATA") + "/" + rows.getString("LOCK_MODE"));
                }
            }
            return new WaitSnapshot(transactions.size(), List.copyOf(locks));
        }
    }

    private void awaitEventsFinished() {
        await().atMost(LIMIT).until(() -> eventExecutor.getActiveCount() == 0
                && eventExecutor.getThreadPoolExecutor().getQueue().isEmpty());
    }

    private BigDecimal amount() {
        return jdbc.queryForObject("SELECT consumed_amount FROM shift_budget_allocations WHERE id = ?",
                BigDecimal.class, fixture.allocationId());
    }

    private List<String> targetStatuses() {
        return jdbc.queryForList("SELECT status FROM shift_budget_consumptions WHERE id IN (?, ?) ORDER BY id",
                String.class, fixture.targetIds().getFirst(), fixture.targetIds().getLast());
    }

    private String status(Long id) {
        return jdbc.queryForObject("SELECT status FROM shift_budget_consumptions WHERE id = ?", String.class, id);
    }
}
