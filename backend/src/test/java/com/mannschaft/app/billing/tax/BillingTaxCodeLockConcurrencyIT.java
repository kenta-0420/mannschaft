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

    private void init() {
        // no-op（互換のため残す。service は @Autowired 済みの本番 Bean を直接使う）。
    }

    @Test
    @DisplayName("AC-11: 異なる2つの新規 code を同時 POST しても __TAX_CODE_LOCK__ 行を介して直列化され両方成功する")
    void ac11_twoDifferentNewCodesBothSucceedSerialized() throws Exception {
        init();
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

        BillingTaxCodeEntity r1 = f1.get(10, TimeUnit.SECONDS);
        BillingTaxCodeEntity r2 = f2.get(10, TimeUnit.SECONDS);
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
