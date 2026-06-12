package com.mannschaft.app.payment.recovery;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.payment.recovery.FeeReconciliationService.CompletionOutcome;
import com.mannschaft.app.payment.recovery.FeeReconciliationService.NetProfitOutcome;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * F22.1 謝礼決済 第三陣 C2: {@link FeeReconciliationBatch} 単体テスト。
 *
 * <p>抽出（read）と実処理（{@code REQUIRES_NEW}）の分離・1 件失敗の継続・集計・バッチ作法
 * （{@code @SchedulerLock}/{@code @BatchEndpoint}/{@code @Scheduled}・Service の {@code REQUIRES_NEW}）を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FeeReconciliationBatch 単体テスト（read/write分離・個別失敗継続・バッチ作法）")
class FeeReconciliationBatchTest {

    @Mock private FeeReconciliationService feeReconciliationService;

    private static final UUID ID1 = UUID.fromString("019607a0-0000-7000-8000-000000000001");
    private static final UUID ID2 = UUID.fromString("019607a0-0000-7000-8000-000000000002");
    private static final UUID ID3 = UUID.fromString("019607a0-0000-7000-8000-000000000003");

    private final Clock clock = Clock.fixed(
            LocalDateTime.of(2026, 6, 12, 3, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

    private FeeReconciliationBatch batch() {
        return new FeeReconciliationBatch(feeReconciliationService, clock);
    }

    @Test
    @DisplayName("補完ループ: COMPLETED/STILL_PENDING/SKIPPED を集計し、1 件の例外でも他件を継続する")
    void completePendingRecoveries_aggregatesAndContinuesOnFailure() {
        FeeReconciliationBatch b = batch();
        given(feeReconciliationService.findPendingRecoveryCandidates()).willReturn(List.of(ID1, ID2, ID3));
        given(feeReconciliationService.completePendingRecovery(ID1)).willReturn(CompletionOutcome.COMPLETED);
        // ID2 は例外（Stripe 通信失敗等）でも握りつぶさず継続。
        given(feeReconciliationService.completePendingRecovery(ID2)).willThrow(new RuntimeException("stripe down"));
        given(feeReconciliationService.completePendingRecovery(ID3)).willReturn(CompletionOutcome.STILL_PENDING);

        FeeReconciliationBatch.ReconcileResult result = b.completePendingRecoveries();

        assertThat(result.completed()).isEqualTo(1);
        assertThat(result.stillPending()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(0);
        // 3 件すべて呼ばれる（ID2 の例外で停止しない）。
        verify(feeReconciliationService).completePendingRecovery(ID1);
        verify(feeReconciliationService).completePendingRecovery(ID2);
        verify(feeReconciliationService).completePendingRecovery(ID3);
    }

    @Test
    @DisplayName("複式検算ループ: 不一致件数を数え、1 件の例外でも他件を継続する")
    void verifyDoubleEntries_countsImbalancesAndContinues() {
        FeeReconciliationBatch b = batch();
        given(feeReconciliationService.findLedgerTransactionIds()).willReturn(List.of(ID1, ID2, ID3));
        given(feeReconciliationService.verifyDoubleEntry(ID1)).willReturn(true);
        given(feeReconciliationService.verifyDoubleEntry(ID2)).willThrow(new RuntimeException("db"));
        given(feeReconciliationService.verifyDoubleEntry(ID3)).willReturn(false);

        int imbalances = b.verifyDoubleEntries();

        assertThat(imbalances).isEqualTo(1);
        verify(feeReconciliationService).verifyDoubleEntry(ID1);
        verify(feeReconciliationService).verifyDoubleEntry(ID3);
    }

    @Test
    @DisplayName("純益突合ループ: applicable のみ合算し pending を別計上、1 件の例外でも継続する")
    void reconcileNetProfit_sumsApplicableAndSkipsPending() {
        FeeReconciliationBatch b = batch();
        LocalDateTime from = LocalDateTime.of(2026, 6, 11, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 6, 12, 0, 0);
        given(feeReconciliationService.findCapturedEscrowIdsForNetProfit(from, to)).willReturn(List.of(ID1, ID2, ID3));
        given(feeReconciliationService.reconcileNetProfit(ID1))
                .willReturn(new NetProfitOutcome(true, false, 500L, 369L, 131L, true));
        given(feeReconciliationService.reconcileNetProfit(ID2))
                .willReturn(new NetProfitOutcome(false, true, 0L, 0L, 0L, false));
        given(feeReconciliationService.reconcileNetProfit(ID3))
                .willReturn(new NetProfitOutcome(true, false, 250L, 180L, 70L, false));

        FeeReconciliationService.NetProfitSummary summary = b.reconcileNetProfit(from, to);

        assertThat(summary.transactionCount()).isEqualTo(2);
        assertThat(summary.pendingCount()).isEqualTo(1);
        assertThat(summary.totalApplicationFee()).isEqualTo(750L);
        assertThat(summary.totalStripeFee()).isEqualTo(549L);
        assertThat(summary.totalNetProfit()).isEqualTo(201L);
        assertThat(summary.feeRecordedCount()).isEqualTo(1);
        assertThat(summary.netProfitRatio()).isEqualTo(201.0 / 750.0);
    }

    @Test
    @DisplayName("バッチ作法: 15分メソッドに @Scheduled(fixedDelay)＋@SchedulerLock＋@BatchEndpoint が付与されている")
    void schedule15Min_hasBatchAnnotations() throws NoSuchMethodException {
        Method m = FeeReconciliationBatch.class.getMethod("reconcileEvery15Min");
        assertThat(m.getAnnotation(Scheduled.class)).isNotNull();
        assertThat(m.getAnnotation(Scheduled.class).fixedDelay()).isEqualTo(900_000L);
        assertThat(m.getAnnotation(SchedulerLock.class)).isNotNull();
        assertThat(m.getAnnotation(BatchEndpoint.class)).isNotNull();
        assertThat(m.getAnnotation(BatchEndpoint.class).name()).isEqualTo("payment-fee-reconciliation");
    }

    @Test
    @DisplayName("バッチ作法: 日次メソッドに @Scheduled(cron)＋@SchedulerLock＋@BatchEndpoint が付与されている")
    void scheduleDaily_hasBatchAnnotations() throws NoSuchMethodException {
        Method m = FeeReconciliationBatch.class.getMethod("reconcileNetProfitDaily");
        assertThat(m.getAnnotation(Scheduled.class)).isNotNull();
        assertThat(m.getAnnotation(Scheduled.class).cron()).isEqualTo("0 0 3 * * *");
        assertThat(m.getAnnotation(SchedulerLock.class)).isNotNull();
        assertThat(m.getAnnotation(BatchEndpoint.class)).isNotNull();
        assertThat(m.getAnnotation(BatchEndpoint.class).name()).isEqualTo("payment-net-profit-reconciliation-daily");
    }

    @Test
    @DisplayName("Service 作法: 書込単位処理に @Transactional(REQUIRES_NEW) が付与されている（context 全滅回避・個別 commit）")
    void serviceWriteMethods_areRequiresNew() throws NoSuchMethodException {
        Method complete = FeeReconciliationService.class.getMethod("completePendingRecovery", UUID.class);
        assertThat(complete.getAnnotation(Transactional.class)).isNotNull();
        assertThat(complete.getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.REQUIRES_NEW);

        Method netProfit = FeeReconciliationService.class.getMethod("reconcileNetProfit", UUID.class);
        assertThat(netProfit.getAnnotation(Transactional.class)).isNotNull();
        assertThat(netProfit.getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }
}
