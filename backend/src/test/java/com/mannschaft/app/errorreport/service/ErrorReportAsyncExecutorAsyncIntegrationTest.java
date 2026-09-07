package com.mannschaft.app.errorreport.service;

import com.mannschaft.app.config.AsyncConfig;
import com.mannschaft.app.errorreport.ErrorReportSeverity;
import com.mannschaft.app.errorreport.entity.ErrorReportEntity;
import com.mannschaft.app.errorreport.repository.ErrorReportRepository;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/**
 * F10.5/F10.6 Phase 10-β 後続-⑥ — @Async プロキシ越しの実行を検証する統合テスト。
 *
 * <p>{@link ErrorReportAsyncExecutor#recordBackendException} が
 * Spring AOP プロキシ経由で呼ばれた場合に、本当に <strong>別スレッド</strong> で実行され
 * 呼び出し側スレッドをブロックしないことを検証する。これは検分で発覚した
 * 「@Async self-invocation でプロキシバイパスしていた」バグの再発を防ぐ
 * リグレッションテストでもある。</p>
 *
 * <p>{@link AsyncConfig} と {@link ErrorReportAsyncExecutor} のみを Spring コンテキストに
 * 立ち上げ、Repository はモック化することで起動コストを抑える。</p>
 *
 * <p>Issue #2990 L11 — Executor は通知ではなく業務イベントを publish する形に変わった。
 * {@code ApplicationEventPublisher} は ApplicationContext 自身が満たすため個別のモックは要らない。
 * 配送リスナーは本コンテキストに載せていないので、publish しても副作用は無い。</p>
 */
@SpringJUnitConfig
@Import({AsyncConfig.class, ErrorReportAsyncExecutor.class})
@DisplayName("ErrorReportAsyncExecutor @Async プロキシ統合テスト (F10.5/F10.6 後続-⑥ リグレッション)")
class ErrorReportAsyncExecutorAsyncIntegrationTest {

    @TestConfiguration
    static class Config {
        // SpringJUnitConfig + @Import で必要な Bean は全て上がる。
        // MockBean を使うため空の TestConfiguration を置く。
    }

    @Autowired
    private ErrorReportAsyncExecutor executor;

    @MockitoBean
    private ErrorReportRepository errorReportRepository;
    /** F10.6 §5.6-③ — 集約バッファ。プロキシ統合テストでは Mock 化して呼び出し回数のみ検証。 */
    @MockitoBean
    private ErrorReportAggregator aggregator;

    @Test
    @DisplayName("recordBackendException は呼び出し側スレッドとは別スレッド（event-pool）で実行される")
    void recordBackendException_runsOnDifferentThread() throws Exception {
        AtomicReference<String> runThreadName = new AtomicReference<>();
        AtomicReference<Long> runThreadId = new AtomicReference<>();
        AtomicReference<String> mdcRequestIdInsideAsync = new AtomicReference<>();

        // Repository.findByErrorHash 呼び出し時点で実行スレッド情報をキャプチャする。
        // doRecordBackendException 内の最初に行われるアクションのため、確実に Async スレッドで動いている。
        given(errorReportRepository.findByErrorHash(anyString()))
                .willAnswer((InvocationOnMock inv) -> {
                    runThreadName.set(Thread.currentThread().getName());
                    runThreadId.set(Thread.currentThread().getId());
                    mdcRequestIdInsideAsync.set(MDC.get("requestId"));
                    return Optional.empty();
                });
        given(errorReportRepository.save(any(ErrorReportEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        Thread callerThread = Thread.currentThread();
        long callerThreadId = callerThread.getId();

        // 呼び出し側スレッドで MDC を設定し、MdcTaskDecorator が Async スレッドへ伝播することも確認する
        MDC.put("requestId", "async-rid-1");
        try {
            executor.recordBackendException(
                    new RuntimeException("async test"),
                    "/api/v1/test",
                    "UA",
                    "127.0.0.1",
                    null,
                    ErrorReportSeverity.MEDIUM);
        } finally {
            MDC.remove("requestId");
        }

        // Async スレッドが処理を完了するまで待つ
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .until(() -> runThreadId.get() != null);

        // 検証: 別スレッド ID で実行されたこと（@Async が実際に proxy 経由で適用されている証拠）
        assertThat(runThreadId.get())
                .as("@Async が proxy 経由で適用されているなら別スレッドで実行されるはず")
                .isNotEqualTo(callerThreadId);

        // 検証: event-pool 由来のスレッド名であること
        assertThat(runThreadName.get())
                .as("event-pool スレッドで実行されるはず")
                .startsWith("event-");

        // 検証: MdcTaskDecorator により MDC（requestId）が Async スレッドへ伝播している
        assertThat(mdcRequestIdInsideAsync.get())
                .as("MdcTaskDecorator が requestId を Async スレッドへ伝播するはず")
                .isEqualTo("async-rid-1");
    }
}
