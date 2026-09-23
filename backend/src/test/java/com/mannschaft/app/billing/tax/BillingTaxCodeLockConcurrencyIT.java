package com.mannschaft.app.billing.tax;

import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 価格改定戦役 第1隊: 税コード専用ロック行 {@code __TAX_CODE_LOCK__} による直列化の実 DB 検証
 * （試練・AC-11。決定6改訂の直接テスト）。
 *
 * <p>第3版までの欠陥（同一 code の既存行だけを FOR UPDATE する方式では、新規 code の初回登録に
 * ロック対象行が存在せず直列化されない）を、**互いに重複しない2つの新規 code を同時 POST** して
 * 反証する。{@link BillingTaxCodeService} / {@link BillingTaxCodeRepository} / {@link BillingTaxCodeEntity}
 * は本試練時点で未実装であり、コンパイルエラーとして red になることを是とする。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("税コードロック行による直列化 IT（AC-10・AC-11）")
class BillingTaxCodeLockConcurrencyIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private BillingTaxCodeRepository repository;

    /**
     * 根治治療（出陣隊第4陣・実測で発見）: {@code new BillingTaxCodeService(repository)} で
     * 手動生成すると Spring AOP のトランザクションプロキシを経由しないため、
     * {@code @Transactional} が一切効かず {@code repository.lockTaxCodeLockRowForUpdate()}
     * （FOR UPDATE クエリ）が「トランザクションが無い」で必ず失敗する
     * （並行性検証IT自体が本コミットまで一度も実行に成功していなかったことを示す）。
     * 本番と同じ Spring 管理 Bean を {@code @Autowired} して初めて、本ITが検証したい
     * ロック行直列化の実際の挙動を確かめられる。
     */
    @Autowired
    private BillingTaxCodeService service;

    /**
     * 殿の指示（実測なしに前例を当てるな）による診断ヘルパー: {@code SHOW ENGINE INNODB STATUS}
     * を取得し、{@code LATEST DETECTED DEADLOCK} 節をテスト失敗メッセージに含める。
     * JDBC 例外は「デッドロックが起きた」ことしか教えず、具体的にどの索引・どのロック種別
     * （gap / insert intention 等）が競合したかは InnoDB の内部状態からしか読めないため。
     */
    private String captureInnodbDeadlockStatus() {
        // アプリの test ユーザーには PROCESS 権限が無く SHOW ENGINE INNODB STATUS が
        // bad SQL grammar（実体は権限不足）になる（実測済み）。本番と乖離した権限を
        // アプリ側のテストユーザーに足すのは避け、診断専用に root で直接接続する
        // （MySQLContainer は root の資格情報も持つ）。
        String rootJdbcUrl = MYSQL.getJdbcUrl();
        try (java.sql.Connection rootConnection = java.sql.DriverManager.getConnection(
                rootJdbcUrl, "root", MYSQL.getPassword());
                java.sql.Statement statement = rootConnection.createStatement();
                java.sql.ResultSet resultSet = statement.executeQuery("SHOW ENGINE INNODB STATUS")) {
            if (!resultSet.next()) {
                return "(SHOW ENGINE INNODB STATUS が1行も返さなかった)";
            }
            String fullStatus = resultSet.getString("Status");
            if (fullStatus == null) {
                return "(Status列がnull)";
            }
            int idx = fullStatus.indexOf("LATEST DETECTED DEADLOCK");
            if (idx < 0) {
                return "(LATEST DETECTED DEADLOCK 節が見つからない。既に他のデッドロックで上書きされたか、"
                        + "InnoDB がまだ検出情報を保持していない可能性がある)";
            }
            int endIdx = fullStatus.indexOf("------------\nTRANSACTIONS", idx);
            return fullStatus.substring(idx, endIdx < 0 ? fullStatus.length() : endIdx);
        } catch (java.sql.SQLException e) {
            return "(root接続でのSHOW ENGINE INNODB STATUS取得に失敗: " + e + ")";
        }
    }

    private void init() {
        // 根治治療（出陣隊第4陣・実測で確定): __TAX_CODE_LOCK__ 行は V220 migration の seed
        // INSERT でのみ投入されるが、application-test.yml は flyway.enabled=false・
        // ddl-auto=create のため、本ITのDBには一切適用されない。ロック行が実在しないと
        // lockTaxCodeLockRowForUpdate() は0件を返し、FOR UPDATEは何も掴まず完全に空振りする
        // （SHOW ENGINE INNODB STATUSのスタックトレースにFOR UPDATEが一切登場しなかったのは
        // これが原因。単なる二次症状ではなく、排他そのものが最初から効いていなかった）。
        // PriceRevisionOverlapConcurrencyIT が plans 行を自前で用意しているのと同じ流儀で、
        // 本ITもロック行を明示的に用意する。
        if (repository.findByCodeAndValidFromAndDeletedAtIsNull("__TAX_CODE_LOCK__", Instant.EPOCH).isEmpty()) {
            repository.save(BillingTaxCodeEntity.builder()
                    .code("__TAX_CODE_LOCK__")
                    .displayName("lock row")
                    .rateBasisPoints(0)
                    .validFrom(Instant.EPOCH)
                    .enabled(false)
                    .build());
        }
    }

    /**
     * 殿の指摘（FOR UPDATE がスタックトレースに一切登場しない＝排他が空振りしている可能性）を
     * 実測で切り分けるための診断: ロック行 {@code __TAX_CODE_LOCK__} が本 IT の DB に
     * 実在するかを直接数える。AC-127 の切り分けで判明済みのとおり、
     * {@code application-test.yml} は {@code flyway.enabled=false}・{@code ddl-auto=create}
     * であり、V220 migration の seed INSERT（ロック行を含む）はテストDBに一切適用されない。
     */
    private long countLockRows() {
        try (java.sql.Connection rootConnection = java.sql.DriverManager.getConnection(
                MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
                java.sql.Statement statement = rootConnection.createStatement();
                java.sql.ResultSet resultSet = statement.executeQuery(
                        "SELECT COUNT(*) AS c FROM billing_tax_codes WHERE code = '__TAX_CODE_LOCK__'")) {
            resultSet.next();
            return resultSet.getLong("c");
        } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("AC-11: 異なる2つの新規 code を同時 POST しても __TAX_CODE_LOCK__ 行を介して直列化され両方成功する")
    void ac11_twoDifferentNewCodesBothSucceedSerialized() throws Exception {
        init();
        long lockRowCount = countLockRows();
        assertThat(lockRowCount)
                .as("ロック行 __TAX_CODE_LOCK__ が本ITのDBに実在するか（0件ならFOR UPDATEは空振りする）")
                .isEqualTo(1L);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);

        Future<BillingTaxCodeEntity> f1 = pool.submit(() -> {
            ready.countDown();
            go.await(5, TimeUnit.SECONDS);
            return service.create(new BillingTaxCodeCreateRequest(
                    "IT_NEW_CODE_A", "新規税コードA", 900, null, Instant.EPOCH, null, true));
        });
        Future<BillingTaxCodeEntity> f2 = pool.submit(() -> {
            ready.countDown();
            go.await(5, TimeUnit.SECONDS);
            return service.create(new BillingTaxCodeCreateRequest(
                    "IT_NEW_CODE_B", "新規税コードB", 700, null, Instant.EPOCH, null, true));
        });

        ready.await(5, TimeUnit.SECONDS);
        go.countDown();

        BillingTaxCodeEntity r1;
        BillingTaxCodeEntity r2;
        try {
            r1 = f1.get(10, TimeUnit.SECONDS);
            r2 = f2.get(10, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            // デッドロックはInnoDB側で即座に検出されるため、Java側の例外を握った直後でも
            // LATEST DETECTED DEADLOCK 節はまだ残っている（実測: 例外検出からここまで数ms）。
            String deadlockStatus = captureInnodbDeadlockStatus();
            pool.shutdown();
            throw new AssertionError(
                    "AC-11実行中に例外（デッドロック等）。SHOW ENGINE INNODB STATUSのLATEST DETECTED "
                            + "DEADLOCK節:\n" + deadlockStatus, e);
        }
        pool.shutdown();

        assertThat(r1.getId()).isNotNull();
        assertThat(r2.getId()).isNotNull();
        List<BillingTaxCodeEntity> all = repository.findAllVisible();
        assertThat(all).extracting(BillingTaxCodeEntity::getCode)
                .contains("IT_NEW_CODE_A", "IT_NEW_CODE_B");
    }

    @Test
    @DisplayName("AC-10直接テスト: 同一 code の重なる有効期間の同時登録は、一方だけ成功し他方は409相当で失敗する")
    void ac10_sameCodeConcurrentOverlap_onlyOneSucceeds() throws Exception {
        init();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failureCount = new AtomicInteger();

        Runnable attempt = () -> {
            try {
                go.await(5, TimeUnit.SECONDS);
                service.create(new BillingTaxCodeCreateRequest(
                        "IT_OVERLAP_CODE", "重複判定用", 1000, null, Instant.EPOCH, null, true));
                successCount.incrementAndGet();
            } catch (Exception e) {
                failureCount.incrementAndGet();
            }
        };
        Future<?> f1 = pool.submit(attempt);
        Future<?> f2 = pool.submit(attempt);
        go.countDown();
        f1.get(10, TimeUnit.SECONDS);
        f2.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failureCount.get()).isEqualTo(1);
    }
}
