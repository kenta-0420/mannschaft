package com.mannschaft.app.role;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;


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
        var token = token(scope("TEAM"), 0, null, 3, 0, null, null);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong owner = new AtomicLong();
        try (var executor = Executors.newFixedThreadPool(2); var observer = observer()) {
            Future<?> blocker = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
                owner.set(connectionId());
                jdbc.queryForObject("SELECT id FROM invite_tokens WHERE id = ? FOR UPDATE", Long.class, token.getId());
                locked.countDown();
                await(release);
            }));
            Future<?> waiter = null;
            try {
                await(locked);
                waiter = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(s ->
                        jdbc.queryForObject("SELECT id FROM invite_tokens WHERE id = ? FOR UPDATE", Long.class, token.getId())));
                // LOCK_DATAに依存しない実辺の存在を先に確認し、環境失敗をredと混同しない。
                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(25)).untilAsserted(() -> {
                    try (var query = observer.prepareStatement("""
                            SELECT COUNT(*) FROM performance_schema.data_lock_waits waits
                            JOIN performance_schema.threads owner ON owner.THREAD_ID = waits.BLOCKING_THREAD_ID
                            WHERE owner.PROCESSLIST_ID = ?
                            """)) {
                        query.setQueryTimeout(1);
                        query.setLong(1, owner.get());
                        try (var rows = query.executeQuery()) {
                            assertThat(rows.next()).isTrue();
                            assertThat(rows.getLong(1)).isEqualTo(1);
                        }
                    }
                });
                try (var query = observer.prepareStatement(projectLockData(rowWaitSql(), nullData))) {
                    query.setQueryTimeout(1);
                    query.setString(1, MYSQL.getDatabaseName());
                    query.setString(2, "invite_tokens");
                    query.setString(3, "%" + token.getId() + "%");
                    query.setLong(4, owner.get());
                    try (var rows = query.executeQuery()) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getLong(1)).as("実辺存在確認後の共有観測SQL nullData=%s", nullData).isEqualTo(1);
                    }
                }
            } finally {
                release.countDown();
                assertThat(outcome(blocker)).isNull();
                if (waiter != null) {
                    assertThat(outcome(waiter)).isNull();
                }
            }
        }
    }

    private Connection observer() throws Exception {
        return DriverManager.getConnection(MYSQL.getJdbcUrl()
                + (MYSQL.getJdbcUrl().contains("?") ? "&" : "?") + "connectTimeout=1000&socketTimeout=1000&useSSL=false&allowPublicKeyRetrieval=true", "root", MYSQL.getPassword());
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
}
