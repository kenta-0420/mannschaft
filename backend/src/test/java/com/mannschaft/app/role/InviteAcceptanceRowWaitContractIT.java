package com.mannschaft.app.role;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 実MySQLの行待機辺に対する観測契約。共有containerとEntity fixtureを用いる。
 * LOCK_DATAだけをNULLに投影する故障注入であり、自然NULLの再現とは区別する。
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("招待承諾の実行ロック待機観測契約")
class InviteAcceptanceRowWaitContractIT extends InviteAcceptanceItSupport {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 実待機辺はLOCK_DATAの両NULL投影でも一件認識する(boolean nullData) throws Exception {
        withRealWait(1, fixture -> {
            assertThat(count(fixture, nullData, "invite_tokens", fixture.proof.blockerConnection, fixture.proof.lockId))
                    .as("実辺存在確認後の共有観測SQL nullData=%s", nullData).isEqualTo(1);
            awaitRowWait(fixture.proof, fixture.waiter);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROW", "OWNER", "TABLE"})
    void 別行の証明または別ownerとtableは実辺があっても一致しない(String mismatch) throws Exception {
        var other = token(scope("TEAM"), 0, null, 3, 0, null, null);
        withRealWait(1, fixture -> {
            if ("ROW".equals(mismatch)) {
                inTx(() -> {
                    lock(other.getId());
                    RowLockProof otherProof = captureTokenLock(connectionId(), other.getId());
                    assertThat(count(fixture, false, "invite_tokens", fixture.proof.blockerConnection, otherProof.lockId)).isZero();
                    return null;
                });
            } else {
                assertThat(count(fixture, false, "TABLE".equals(mismatch) ? "users" : "invite_tokens",
                        fixture.proof.blockerConnection + ("OWNER".equals(mismatch) ? 1 : 0), fixture.proof.lockId)).isZero();
            }
        });
    }

    @Test
    void 保持PRIMARYがゼロならcaptureを拒否する() {
        inTx(() -> {
            assertThatThrownBy(() -> captureTokenLock(connectionId(), 0L)).isInstanceOf(AssertionError.class)
                    .hasMessageContaining("0件");
            return null;
        });
    }

    @Test
    void 保持PRIMARYが二行ならcaptureを拒否する() {
        var scope = scope("TEAM");
        var first = token(scope, 0, null, 3, 0, null, null);
        var second = token(scope, 0, null, 3, 0, null, null);
        inTx(() -> {
            lock(first.getId());
            lock(second.getId());
            assertThatThrownBy(() -> captureTokenLock(connectionId(), first.getId())).isInstanceOf(AssertionError.class)
                    .hasMessageContaining("複数件");
            return null;
        });
    }

    @Test
    void 他接続をownerとしてcaptureできない() {
        var target = token(scope("TEAM"), 0, null, 3, 0, null, null);
        inTx(() -> {
            lock(target.getId());
            assertThatThrownBy(() -> captureTokenLock(connectionId() + 1, target.getId())).isInstanceOf(AssertionError.class)
                    .hasMessageContaining("同じ接続");
            return null;
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void commitとrollback後の証明は新しい保持TXでも再利用できない(boolean rollback) {
        var target = token(scope("TEAM"), 0, null, 3, 0, null, null);
        RowLockProof proof = new TransactionTemplate(transactionManager).execute(status -> {
            lock(target.getId());
            RowLockProof captured = captureTokenLock(connectionId(), target.getId());
            if (rollback) {
                status.setRollbackOnly();
            }
            return captured;
        });
        inTx(() -> {
            lock(target.getId());
            assertThatThrownBy(proof::requireActive).isInstanceOf(AssertionError.class).hasMessageContaining("再利用しない");
            return null;
        });
    }

    @Test
    void 同じ保持TXで別lockを再captureして救済できない() {
        var scope = scope("TEAM");
        var first = token(scope, 0, null, 3, 0, null, null);
        var second = token(scope, 0, null, 3, 0, null, null);
        inTx(() -> {
            lock(first.getId());
            captureTokenLock(connectionId(), first.getId());
            lock(second.getId());
            assertThatThrownBy(() -> captureTokenLock(connectionId(), second.getId())).isInstanceOf(AssertionError.class)
                    .hasMessageContaining("再capture");
            return null;
        });
    }

    @Test
    void 同じtargetに実waiterが二本なら成功扱いしない() throws Exception {
        withRealWait(2, fixture -> {
            assertThat(count(fixture, false, "invite_tokens", fixture.proof.blockerConnection, fixture.proof.lockId)).isEqualTo(2);
            assertThatThrownBy(() -> awaitRowWait(fixture.proof, fixture.waiter)).isInstanceOf(AssertionError.class)
                    .hasMessageContaining("複数");
        });
    }

    /** UUID tokenは新scope内に作り、ここで開始するworker以外へ渡さない。 */
    private void withRealWait(int waiterCount, Consumer<WaitFixture> verify) throws Exception {
        var token = token(scope("TEAM"), 0, null, 3, 0, null, null);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<RowLockProof> captured = new AtomicReference<>();
        List<Future<?>> waiters = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(waiterCount + 1); var observer = rowWaitObserver()) {
            Future<?> blocker = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
                try {
                    lock(token.getId());
                    captured.set(captureTokenLock(connectionId(), token.getId()));
                } finally {
                    locked.countDown();
                }
                await(release);
            }));
            try {
                await(locked);
                RowLockProof proof = capturedProof(captured, blocker);
                for (int i = 0; i < waiterCount; i++) {
                    waiters.add(executor.submit(() -> new TransactionTemplate(transactionManager)
                            .executeWithoutResult(s -> lock(token.getId()))));
                }
                // LOCK_DATAに依存しない実辺を先に確認し、環境失敗をAC成功/失敗と混同しない。
                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(25))
                        .untilAsserted(() -> {
                            try (var query = observer.prepareStatement("""
                                    SELECT COUNT(*) FROM performance_schema.data_lock_waits waits
                                    JOIN performance_schema.threads owner ON owner.THREAD_ID = waits.BLOCKING_THREAD_ID
                                    WHERE owner.PROCESSLIST_ID = ?
                                    """)) {
                                query.setQueryTimeout(1);
                                query.setLong(1, proof.blockerConnection);
                                try (var rows = query.executeQuery()) {
                                    assertThat(rows.next()).isTrue();
                                    assertThat(rows.getLong(1)).isEqualTo(waiterCount);
                                }
                            }
                        });
                verify.accept(new WaitFixture(proof, observer, waiters.getFirst()));
            } finally {
                release.countDown();
                assertThat(outcome(blocker)).isNull();
                for (Future<?> waiter : waiters) {
                    assertThat(outcome(waiter)).isNull();
                }
            }
        }
    }

    private void lock(Long rowId) {
        jdbc.queryForObject("SELECT id FROM invite_tokens WHERE id = ? FOR UPDATE", Long.class, rowId);
    }

    private long count(WaitFixture fixture, boolean nullData, String table, long owner, String lockId) {
        fixture.proof.requireActive();
        try (var query = fixture.observer.prepareStatement(projectLockData(rowWaitSql(), nullData))) {
            query.setQueryTimeout(1);
            query.setString(1, MYSQL.getDatabaseName());
            query.setString(2, table);
            query.setLong(3, owner);
            query.setString(4, fixture.proof.engine);
            query.setString(5, lockId);
            try (var rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getLong(1);
            }
        } catch (SQLException failure) {
            throw new AssertionError("共有観測SQLの契約検査に失敗しました", failure);
        }
    }

    /** 元SQLを共有し、実locksのLOCK_DATA列だけを欠損させる。他の値とwaitsは原値のまま。 */
    private static String projectLockData(String sql, boolean nullData) {
        if (!nullData) {
            return sql;
        }
        return sql.replace("performance_schema.data_locks", """
                (SELECT ENGINE, ENGINE_LOCK_ID, ENGINE_TRANSACTION_ID, THREAD_ID, EVENT_ID,
                        OBJECT_SCHEMA, OBJECT_NAME, PARTITION_NAME, SUBPARTITION_NAME, INDEX_NAME,
                        OBJECT_INSTANCE_BEGIN, LOCK_TYPE, LOCK_MODE, LOCK_STATUS,
                        CAST(NULL AS CHAR) AS LOCK_DATA FROM performance_schema.data_locks)
                """);
    }

    /** 当該実待機の観測値だけを保持し、Java threadとの独立した接続同定とは扱わない。 */
    private record WaitFixture(RowLockProof proof, Connection observer, Future<?> waiter) { }
}