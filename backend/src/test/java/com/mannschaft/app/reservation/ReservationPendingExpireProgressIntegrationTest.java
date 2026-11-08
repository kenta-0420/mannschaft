package com.mannschaft.app.reservation;

import com.mannschaft.app.reservation.entity.ReservationPendingExpireScanStateEntity;
import com.mannschaft.app.reservation.repository.ReservationPendingExpireScanStateRepository;
import com.mannschaft.app.reservation.service.ReservationPendingExpireBatchService;
import com.mannschaft.app.reservation.service.ReservationPendingExpireProgressService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * 進捗TX骨格の実DB試験候補。独自のSpring/DB設定・Bean置換は追加しない。
 * 公開batchへ未接続のため、最後の契約は現実装でREDを期待する。未実行。
 * 共通設定整合後に本クラスを単独選択する。既期限/TZ試験は別に維持する。
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("仮押さえ失効 専用進捗の取引境界")
class ReservationPendingExpireProgressIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private ReservationPendingExpireScanStateRepository repository;
    @Autowired
    private ReservationPendingExpireProgressService progress;
    @Autowired
    private ReservationPendingExpireBatchService batch;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private Clock clock;
    @Autowired
    private JdbcTemplate jdbc;

    private boolean createdState;
    private UUID stateId;
    private boolean workersStopped = true;

    @BeforeEach
    void 専用試験行を作る() {
        // 未作成時の失敗では旧行を削除しない。schedulerは共通test設定で停止する。
        assertThat(repository.count()).as("他runnerの進捗を上書きしない").isZero();
        stateId = repository.saveAndFlush(ReservationPendingExpireTestFixture.minimumValidState(clock.instant())).getId();
        createdState = true;
    }

    @AfterEach
    void 作成した専用試験行だけ片付ける() {
        assertThat(workersStopped).as("生存workerがあるときはstate削除を拒否する").isTrue();
        if (createdState) {
            // 不正JSONの試験後でもEntityを再hydrateせず、この試験の生成行だけ削除する。
            jdbc.update("DELETE FROM reservation_pending_expire_scan_state WHERE id = UNHEX(REPLACE(?, '-', ''))",
                    stateId.toString());
        }
    }

    @Test
    void 耐久投入は呼出元のrollbackでも残る() {
        var run = progress.beginRun(20);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            progress.enqueue(run.epoch(), 10);
            status.setRollbackOnly();
        });
        assertThat(reload().getRetryPrimaryIds()).containsExactly(10L);
        assertThat(reload().getLastInspectedId()).isZero();
    }

    @Test
    void unit参加の完了更新はunitと一緒にrollbackする() {
        var run = progress.beginRun(20);
        progress.enqueue(run.epoch(), 10);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            progress.lockForUnit(run.epoch());
            progress.completeUnit(run.epoch(), 10, 10);
            status.setRollbackOnly();
        });
        assertThat(reload().getRetryPrimaryIds()).containsExactly(10L);
        assertThat(reload().getLastInspectedId()).isZero();
    }

    @Test
    void 新epoch取得後の旧runnerの耐久投入を拒否する() {
        var oldRun = progress.beginRun(20);
        var newRun = progress.beginRun(999);
        assertThat(newRun.highWater()).isEqualTo(20);
        assertThatThrownBy(() -> progress.enqueue(oldRun.epoch(), 10))
                .isInstanceOf(IllegalStateException.class);
        assertThat(reload().getRetryPrimaryIds()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"[0]", "[-1]", "[null]", "[1,1]", "[1.5]", "[\"1\"]",
            "[9223372036854775808]", "[true]", "[[]]", "[{}]", "{}"})
    void 壊れた再試行要素を正常な進捗として採用しない(String json) {
        jdbc.update("UPDATE reservation_pending_expire_scan_state SET retry_primary_ids = ? WHERE singleton_key = 1", json);
        assertThatThrownBy(() -> progress.beginRun(100)).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject(
                "SELECT run_epoch FROM reservation_pending_expire_scan_state WHERE singleton_key = 1", Long.class)).isZero();
        // List<Long>への小数/文字列coercionも拒否する契約。現骨格でREDの可能性を残す。
    }

    @Test
    void 正Longの最大値を丸めずに再読する() {
        jdbc.update("UPDATE reservation_pending_expire_scan_state SET retry_primary_ids = ? WHERE singleton_key = 1",
                "[9223372036854775807]");
        var run = progress.beginRun(100);
        assertThat(run.retryPrimaryIds()).containsExactly(Long.MAX_VALUE);
    }

    @Test
    void 構文が壊れたJSONは実JSON列が拒否する() {
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE reservation_pending_expire_scan_state SET retry_primary_ids = ? WHERE singleton_key = 1", "["))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(reload().getRetryPrimaryIds()).isEmpty();
    }

    @Test
    void 生JSONに501件ある場合も正常進捗へ採用しない() {
        String json = LongStream.rangeClosed(1, 501).mapToObj(value -> Long.toString(value))
                .collect(Collectors.joining(",", "[", "]"));
        jdbc.update("UPDATE reservation_pending_expire_scan_state SET retry_primary_ids = ? WHERE singleton_key = 1", json);
        assertThatThrownBy(() -> progress.beginRun(1000)).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject(
                "SELECT run_epoch FROM reservation_pending_expire_scan_state WHERE singleton_key = 1", Long.class)).isZero();
    }

    @Test
    void 再試行499件から500件だけ追加でき501件目は残さない() {
        String json = LongStream.rangeClosed(1, 499).mapToObj(value -> Long.toString(value))
                .collect(Collectors.joining(",", "[", "]"));
        jdbc.update("UPDATE reservation_pending_expire_scan_state SET retry_primary_ids = ? WHERE singleton_key = 1", json);
        var run = progress.beginRun(1000);
        assertThat(run.retryPrimaryIds()).hasSize(499);
        progress.enqueue(run.epoch(), 500);
        assertThat(reload().getRetryPrimaryIds()).containsExactlyElementsOf(
                LongStream.rangeClosed(1, 500).boxed().toList());
        progress.enqueue(run.epoch(), 500); // 既IDの再投入は件数を増やさない。
        assertThatThrownBy(() -> progress.enqueue(run.epoch(), 501)).isInstanceOf(IllegalStateException.class);
        assertThat(reload().getRetryPrimaryIds()).containsExactlyElementsOf(
                LongStream.rangeClosed(1, 500).boxed().toList());
    }

    @Test
    void 旧unitが保持する実DB行lockの解放まで新epochは取得できない() throws Exception {
        var oldRun = progress.beginRun(20);
        var locked = new CountDownLatch(1);
        var holderConnectionId = new AtomicLong();
        var release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        workersStopped = false;
        var holder = executor.submit(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> {
                    progress.lockForUnit(oldRun.epoch());
                    holderConnectionId.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                    locked.countDown();
                    try {
                        if (!release.await(20, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("試験のunit lock解放待ちが時間切れです");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("試験のunitが中断されました", e);
                    }
                }));
        try {
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var next = executor.submit(() -> progress.beginRun(999));
            await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(50))
                    .until(() -> waitingSingletonLocks(holderConnectionId.get()) == 1);
            assertThat(next.isDone()).as("実行世代を旧unitのcommit前に変えない").isFalse();
            assertThat(reload().getRunEpoch()).isEqualTo(oldRun.epoch());
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            var nextRun = next.get(10, TimeUnit.SECONDS);
            assertThat(nextRun.epoch()).isEqualTo(oldRun.epoch() + 1);
            assertThat(nextRun.highWater()).isEqualTo(20);
            assertThatThrownBy(() -> progress.enqueue(oldRun.epoch(), 10))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(reload().getRetryPrimaryIds()).isEmpty();
        } finally {
            release.countDown();
            executor.shutdown();
            workersStopped = executor.awaitTermination(15, TimeUnit.SECONDS);
            if (!workersStopped) {
                executor.shutdownNow();
                workersStopped = executor.awaitTermination(10, TimeUnit.SECONDS);
            }
            assertThat(workersStopped).as("state cleanup前に全owned workerが終了する").isTrue();
            holder.get(1, TimeUnit.SECONDS);
        }
    }

    /** 既MarketFinalize ITと同じ、所有MySQL root別接続による実lock観測。秘密を出力しない。 */
    private static long waitingSingletonLocks(long holderConnectionId) throws SQLException {
        try (Connection connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM performance_schema.data_lock_waits waits"
                             + " JOIN performance_schema.data_locks requested"
                             + " ON requested.ENGINE = waits.ENGINE"
                             + " AND requested.ENGINE_LOCK_ID = waits.REQUESTING_ENGINE_LOCK_ID"
                             + " JOIN performance_schema.threads holder ON holder.THREAD_ID = waits.BLOCKING_THREAD_ID"
                             + " WHERE requested.OBJECT_SCHEMA = ?"
                             + " AND requested.OBJECT_NAME = 'reservation_pending_expire_scan_state'"
                             + " AND requested.INDEX_NAME = 'uq_rpess_singleton'"
                             + " AND requested.LOCK_STATUS = 'WAITING' AND holder.PROCESSLIST_ID = ?")) {
            statement.setString(1, MYSQL.getDatabaseName());
            statement.setLong(2, holderConnectionId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    @Test
    void publicバッチも専用進捗のepochを取得する() {
        // 共有DB内の他ドメインfixtureは追加・削除しない。本クラス単独実行で評価する。
        batch.expirePendingReservations();
        assertThat(reload().getRunEpoch()).as("未接続の現public入口でREDを期待").isEqualTo(1);
    }

    private ReservationPendingExpireScanStateEntity reload() {
        return repository.findById(stateId).orElseThrow();
    }
}
