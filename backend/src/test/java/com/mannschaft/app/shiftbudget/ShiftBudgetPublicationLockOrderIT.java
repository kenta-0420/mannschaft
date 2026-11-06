package com.mannschaft.app.shiftbudget;

import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import com.mannschaft.app.shiftbudget.port.ShiftBudgetPublicationPort;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetConsumptionRepository;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetConsumptionService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.TeamOrgFixtureHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CMP-261021-0718 AC13/15: 実サービスの親ロック寿命と取消claim順を実MySQLで観測する。
 *
 * <p>テスト全体をTXで包まず、B0の実Service proxy・実commitを使う。recordだけは
 * 明示外側TXをrollbackし、REQUIRES_NEWの消化がcommit済みで残ることも確認する。専用StatementInspectorはSQLを
 * 変更せず、観測対象workerだけをThreadLocalで選ぶ。取消観測の追加SELECTは同TXの
 * nonlocking read-your-writesであり、業務ロックの取得順や戻り値を変更しない。
 * 専用observerを登録するcontextだけをAFTER_CLASSで閉じる。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@TestPropertySource(properties = {
        "feature.shift-budget.enabled=true",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector="
                + "com.mannschaft.app.shiftbudget.ShiftBudgetPublicationLockOrderIT$LockSqlObserver"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("0718 親ロックと割当・消化の共通取得順（実DB）")
public class ShiftBudgetPublicationLockOrderIT extends AbstractMySqlIntegrationTest {
    private static final long GENERATION = 1L;
    private static final long ACTOR_ID = 9173L;
    private static final long LIMIT_SECONDS = 30L;
    private static final BigDecimal ROW_AMOUNT = new BigDecimal("1000.00");
    private static final BigDecimal OTHER_SHIFT_AMOUNT = new BigDecimal("10000.00");

    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private ShiftBudgetConsumptionService service;
    @Autowired private ShiftBudgetPublicationPort publicationPort;
    @Autowired private ShiftScheduleRepository schedules;
    @Autowired private ShiftSlotRepository slots;
    @Autowired private ShiftBudgetAllocationRepository allocations;
    @Autowired private ShiftBudgetConsumptionRepository consumptions;
    @PersistenceContext private EntityManager em;

    private Long organizationId;
    private Long teamId;
    private boolean workersStopped = true;
    private final List<Long> ownSchedules = new ArrayList<>();
    private final List<Long> ownAllocations = new ArrayList<>();

    @BeforeEach
    void setUp() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        String nonce = UUID.randomUUID().toString().replace("-", "").substring(0, 28);
        transactionTemplate.executeWithoutResult(tx -> {
            organizationId = TeamOrgFixtureHelper.insertOrganization(em, "0718ロック組織", "o-" + nonce);
            teamId = TeamOrgFixtureHelper.insertTeam(em, "0718ロックチーム", "t-" + nonce);
        });
    }

    @AfterEach
    void cleanup() {
        // 各testのfinallyでworkerを回収してから、自分のfixtureだけを撤去する。
        assertThat(workersStopped).as("実TX workerを回収できない場合はfixtureを削除しない").isTrue();
        for (Long shiftId : ownSchedules) {
            jdbc.update("DELETE FROM shift_budget_consumptions WHERE shift_id = ?", shiftId);
            jdbc.update("DELETE FROM shift_slots WHERE schedule_id = ?", shiftId);
            jdbc.update("DELETE FROM shift_schedules WHERE id = ?", shiftId);
        }
        for (Long allocationId : ownAllocations) {
            jdbc.update("DELETE FROM shift_budget_allocations WHERE id = ?", allocationId);
        }
        if (teamId != null) jdbc.update("DELETE FROM teams WHERE id = ?", teamId);
        if (organizationId != null) jdbc.update("DELETE FROM organizations WHERE id = ?", organizationId);
    }

    @Test
    @Timeout(90)
    @DisplayName("AC13 recordの先頭SQLが親FOR UPDATEで、そのロックは予算TXのcommitまで残る")
    void 記録の親ロックを予算TXのcommitまで保持する() throws Exception {
        long[] fixture = transactionTemplate.execute(tx -> {
            Long shiftId = savePublishedSchedule();
            Long slotId = slots.save(ShiftBudgetPublicationGenerationTestFixture.slot(shiftId, ACTOR_ID)).getId();
            return new long[] {shiftId, slotId, saveAllocation(BigDecimal.ZERO, 0)};
        });
        CountDownLatch beforeCommit = new CountDownLatch(1);
        CountDownLatch releaseCommit = new CountDownLatch(1);
        Observation observation = new Observation(dataSource, fixture[0], List.of(), beforeCommit, releaseCommit);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        workersStopped = false;
        Future<Boolean> result = executor.submit(() -> observe(observation, () ->
                transactionTemplate.execute(outer -> {
                    boolean recorded = service.recordSingleConsumption(fixture[2], fixture[0], fixture[1], ACTOR_ID,
                            ROW_AMOUNT, BigDecimal.ONE, GENERATION);
                    outer.setRollbackOnly();
                    return recorded;
                })));
        try {
            assertThat(beforeCommit.await(LIMIT_SECONDS, TimeUnit.SECONDS))
                    .as("recordの実TXがcommit直前へ到達する").isTrue();
            assertParentFirst(observation);
            assertThat(observation.transactionName).endsWith(".recordSingleConsumption");
            assertThatThrownBy(() -> lockParentNowait(fixture[0]))
                    .as("未commitの親行を別接続は取得できない")
                    .isInstanceOfSatisfying(SQLException.class, failure -> {
                        assertThat(failure.getErrorCode()).as("実MySQLのNOWAITロック拒否").isEqualTo(3572);
                        assertThat(failure.getSQLState()).isEqualTo("HY000");
                    });

            releaseCommit.countDown();
            assertThat(result.get(LIMIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            assertParentFirst(observation);
            assertThat(lockParentNowait(fixture[0])).as("commit後は同じ親行を取得できる").isEqualTo(fixture[0]);
            assertThat(amount(fixture[2])).as("外側TXのrollback後もREQUIRES_NEWの実commitは残る")
                    .isEqualByComparingTo(ROW_AMOUNT);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shift_budget_consumptions "
                    + "WHERE shift_id = ? AND status = 'PLANNED' AND publication_generation = ?",
                    Integer.class, fixture[0], GENERATION)).isEqualTo(1);
        } finally {
            releaseCommit.countDown();
            stop(executor, List.of(result));
            workersStopped = true;
        }
    }

    @Test
    @DisplayName("AC13 adapterの実proxyをTXなしで単独呼出しするとMANDATORYが拒否する")
    void 親ロックadapterは呼出元TXを必須とする() {
        Long shiftId = transactionTemplate.execute(tx -> savePublishedSchedule());
        assertThat(AopUtils.isAopProxy(publicationPort)).isTrue();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();

        assertThatThrownBy(() -> publicationPort.lockPublication(shiftId))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @Timeout(120)
    @DisplayName("AC15 逆向き消化PKの2シフトも割当ID→消化IDの実claim順で並行取消し実額を保つ")
    void 共通割当を逆PKで持つ取消も同じ順序で完走する() throws Exception {
        RaceFixture fixture = transactionTemplate.execute(tx -> {
            Long firstShift = savePublishedSchedule();
            Long secondShift = savePublishedSchedule();
            Long otherShift = savePublishedSchedule();
            BigDecimal total = ROW_AMOUNT.multiply(BigDecimal.valueOf(4)).add(OTHER_SHIFT_AMOUNT);
            Long allocationA = saveAllocation(total, 0);
            Long allocationB = saveAllocation(total, 1);
            // 両割当に各2行。PK順と割当順を食い違わせ、2次キーの消化ID順も観測する。
            Long firstA1 = savePlanned(firstShift, allocationA);
            Long firstB1 = savePlanned(firstShift, allocationB);
            Long firstA2 = savePlanned(firstShift, allocationA);
            Long firstB2 = savePlanned(firstShift, allocationB);
            Long secondB1 = savePlanned(secondShift, allocationB);
            Long secondA1 = savePlanned(secondShift, allocationA);
            Long secondB2 = savePlanned(secondShift, allocationB);
            Long secondA2 = savePlanned(secondShift, allocationA);
            savePlanned(otherShift, allocationA, OTHER_SHIFT_AMOUNT);
            savePlanned(otherShift, allocationB, OTHER_SHIFT_AMOUNT);
            assertThat(allocationA).isLessThan(allocationB);
            assertThat(firstA1).isLessThan(firstB1);
            assertThat(secondB1).isLessThan(secondA1);
            return new RaceFixture(firstShift, secondShift, allocationA, allocationB,
                    List.of(firstA1, firstA2, firstB1, firstB2),
                    List.of(secondA1, secondA2, secondB1, secondB2));
        });
        Observation first = new Observation(dataSource, fixture.firstShift(), fixture.firstOrder(), null, null);
        Observation second = new Observation(dataSource, fixture.secondShift(), fixture.secondOrder(), null, null);
        // 最初の割当ロックSQLを実行する前に両TXを重ねる。共通資源取得後には待ち合わせない。
        CyclicBarrier allocationLockStart = new CyclicBarrier(2);
        first.allocationLockStart = allocationLockStart;
        second.allocationLockStart = allocationLockStart;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        workersStopped = false;
        List<Future<ShiftBudgetConsumptionService.PublicationCleanup>> results = new ArrayList<>();
        try {
            for (Observation observation : List.of(first, second)) {
                results.add(executor.submit(() -> observe(observation, () ->
                        service.cancelOldPlannedForPublication(observation.shiftId, GENERATION,
                                Set.of(organizationId)))));
            }
            for (Future<ShiftBudgetConsumptionService.PublicationCleanup> result : results) {
                var cleanup = result.get(LIMIT_SECONDS, TimeUnit.SECONDS);
                assertThat(cleanup.accepted()).isTrue();
                assertThat(cleanup.cancellation().cancelledCount()).isEqualTo(4);
            }
            for (Observation observation : List.of(first, second)) {
                assertParentFirst(observation);
                assertThat(observation.claimOrder).containsExactlyElementsOf(observation.expectedOrder);
                assertThat(jdbc.queryForList("SELECT status FROM shift_budget_consumptions WHERE shift_id = ?",
                        String.class, observation.shiftId)).containsExactlyInAnyOrder(
                        "CANCELLED", "CANCELLED", "CANCELLED", "CANCELLED");
                assertThat(service.cancelOldPlannedForPublication(observation.shiftId, GENERATION, Set.of(organizationId))
                        .cancellation().cancelledCount()).as("同じ取消の再実行は再claimしない").isZero();
            }
            assertThat(amount(fixture.allocationA())).as("対象外シフトの残額を二重減算しない")
                    .isEqualByComparingTo(OTHER_SHIFT_AMOUNT);
            assertThat(amount(fixture.allocationB())).isEqualByComparingTo(OTHER_SHIFT_AMOUNT);
        } finally {
            stop(executor, results);
            workersStopped = true;
        }
    }

    private Long savePublishedSchedule() {
        ShiftScheduleEntity schedule = ShiftBudgetPublicationGenerationTestFixture.schedule(teamId, ACTOR_ID);
        schedule.publish(ACTOR_ID);
        schedule.setPublicationGeneration(GENERATION);
        Long id = schedules.save(schedule).getId();
        ownSchedules.add(id);
        return id;
    }

    private Long saveAllocation(BigDecimal consumed, int monthOffset) {
        LocalDate start = ShiftBudgetPublicationGenerationTestFixture.SLOT_DATE.plusMonths(monthOffset);
        Long id = allocations.save(ShiftBudgetConsumptionCancelRaceTestFixture
                .allocation(organizationId, teamId, consumed).toBuilder()
                .periodStart(start).periodEnd(start.withDayOfMonth(start.lengthOfMonth())).build()).getId();
        ownAllocations.add(id);
        return id;
    }

    private Long savePlanned(Long shiftId, Long allocationId) {
        return savePlanned(shiftId, allocationId, ROW_AMOUNT);
    }

    private Long savePlanned(Long shiftId, Long allocationId, BigDecimal amount) {
        Long slotId = slots.save(ShiftBudgetConsumptionCancelRaceTestFixture.slot(shiftId)).getId();
        return consumptions.save(ShiftBudgetConsumptionCancelRaceTestFixture.consumption(
                allocationId, shiftId, slotId, amount, ShiftBudgetConsumptionStatus.PLANNED)).getId();
    }

    private BigDecimal amount(Long allocationId) {
        return jdbc.queryForObject("SELECT consumed_amount FROM shift_budget_allocations WHERE id = ?",
                BigDecimal.class, allocationId);
    }

    private long lockParentNowait(long shiftId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var query = connection.prepareStatement("SELECT id FROM shift_schedules WHERE id = ? FOR UPDATE NOWAIT")) {
                query.setQueryTimeout((int) LIMIT_SECONDS);
                query.setLong(1, shiftId);
                try (var rows = query.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    return rows.getLong(1);
                }
            } finally {
                connection.rollback();
            }
        }
    }

    private static void assertParentFirst(Observation observation) {
        assertThat(observation.sqls).isNotEmpty();
        assertThat(observation.sqls.getFirst()).as("最初の業務SQL").startsWith("select ")
                .contains(" from shift_schedules ", " for update");
        // 現行Hibernateの非修飾・非引用テーブル名に対応し、予算テーブルへのDMLは許可する。
        assertThat(observation.sqls).as("親シフト表は行ロックと参照だけで、書き込まない")
                .noneMatch(sql -> sql.matches(
                        "^(insert into|update|delete from|replace( into)?) shift_schedules( |\\().*"));
    }

    private static <T> T observe(Observation observation, Callable<T> action) throws Exception {
        assertThat(LockSqlObserver.CURRENT.get()).isNull();
        LockSqlObserver.CURRENT.set(observation);
        try {
            return action.call();
        } finally {
            LockSqlObserver.CURRENT.remove();
        }
    }

    private static void stop(ExecutorService executor, List<? extends Future<?>> futures) throws InterruptedException {
        for (Future<?> future : futures) {
            if (!future.isDone()) future.cancel(true);
        }
        executor.shutdownNow();
        assertThat(executor.awaitTermination(LIMIT_SECONDS, TimeUnit.SECONDS)).as("実TX workerの有限回収").isTrue();
    }

    private record RaceFixture(Long firstShift, Long secondShift, Long allocationA, Long allocationB,
                               List<Long> firstOrder, List<Long> secondOrder) { }

    /** Hibernate標準の拡張点。SQLをそのまま返し、観測窓以外の実行には何もしない。 */
    public static final class LockSqlObserver implements StatementInspector {
        private static final ThreadLocal<Observation> CURRENT = new ThreadLocal<>();

        @Override
        public String inspect(String sql) {
            Observation observation = CURRENT.get();
            if (observation != null) observation.inspect(sql);
            return sql;
        }
    }

    private static final class Observation {
        private final DataSource dataSource;
        private final Long shiftId;
        private final List<Long> expectedOrder;
        private final CountDownLatch beforeCommit;
        private final CountDownLatch releaseCommit;
        private final List<String> sqls = new ArrayList<>();
        private final List<Long> claimOrder = new ArrayList<>();
        private CyclicBarrier allocationLockStart;
        private boolean enteredAllocationLock;
        private String transactionName;

        private Observation(DataSource dataSource, Long shiftId, List<Long> expectedOrder,
                            CountDownLatch beforeCommit, CountDownLatch releaseCommit) {
            this.dataSource = dataSource;
            this.shiftId = shiftId;
            this.expectedOrder = expectedOrder;
            this.beforeCommit = beforeCommit;
            this.releaseCommit = releaseCommit;
        }

        private void inspect(String sql) {
            String normalized = sql.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("\\s+", " ")
                    .trim().toLowerCase(Locale.ROOT);
            if (sqls.isEmpty()) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                transactionName = TransactionSynchronizationManager.getCurrentTransactionName();
                if (beforeCommit != null) registerCommitBarrier();
            }
            sqls.add(normalized);
            if (!enteredAllocationLock && normalized.contains("shift_budget_allocations")
                    && (normalized.startsWith("update ") || normalized.contains(" for update")
                    || normalized.contains(" for share"))) {
                enteredAllocationLock = true;
                awaitAllocationSqlEntry();
            }
            if (!expectedOrder.isEmpty() && normalized.startsWith("update shift_budget_allocations ")) {
                observeClaimPrefix();
            }
        }

        /** StatementInspectorはSQL実行前に呼ばれる。共通allocationロックはまだどちらも保持していない。 */
        private void awaitAllocationSqlEntry() {
            if (allocationLockStart == null) return;
            try {
                allocationLockStart.await(LIMIT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError("取消TXの重複同期が割り込まれた", failure);
            } catch (java.util.concurrent.BrokenBarrierException | java.util.concurrent.TimeoutException failure) {
                throw new AssertionError("両取消TXが共通割当SQLの実行直前へ到達しなかった", failure);
            }
        }

        private void registerCommitBarrier() {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    beforeCommit.countDown();
                    try {
                        assertThat(releaseCommit.await(LIMIT_SECONDS, TimeUnit.SECONDS))
                                .as("commit待機はfinallyで必ず解放する").isTrue();
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("commit同期が割り込まれた", failure);
                    }
                }
            });
        }

        /** 各減算直前の実CANCELLED集合を取り、直前のprefixから増えた実行済みclaimだけを記録する。 */
        private void observeClaimPrefix() {
            Connection connection = DataSourceUtils.getConnection(dataSource);
            try {
                assertThat(DataSourceUtils.isConnectionTransactional(connection, dataSource)).isTrue();
                Set<Long> cancelled = new HashSet<>();
                try (var query = connection.prepareStatement("SELECT id FROM shift_budget_consumptions "
                        + "WHERE shift_id = ? AND status = 'CANCELLED' AND deleted_at IS NULL")) {
                    query.setQueryTimeout((int) LIMIT_SECONDS);
                    query.setLong(1, shiftId);
                    try (var rows = query.executeQuery()) {
                        while (rows.next()) cancelled.add(rows.getLong(1));
                    }
                }
                Set<Long> added = new HashSet<>(cancelled);
                added.removeAll(claimOrder);
                assertThat(added).as("各減算の直前に実行済みの新claimは1行").hasSize(1);
                claimOrder.add(added.iterator().next());
                assertThat(claimOrder).as("割当ID→消化ID順の実claim prefix")
                        .containsExactlyElementsOf(expectedOrder.subList(0, claimOrder.size()));
                assertThat(cancelled).containsExactlyInAnyOrderElementsOf(claimOrder);
            } catch (SQLException failure) {
                throw new AssertionError("同TXのnonlocking claim観測SELECTが失敗", failure);
            } finally {
                DataSourceUtils.releaseConnection(connection, dataSource);
            }
        }
    }
}
