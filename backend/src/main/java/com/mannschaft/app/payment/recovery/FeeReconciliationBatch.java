package com.mannschaft.app.payment.recovery;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.payment.recovery.FeeReconciliationService.CompletionOutcome;
import com.mannschaft.app.payment.recovery.FeeReconciliationService.NetProfitOutcome;
import com.mannschaft.app.payment.recovery.FeeReconciliationService.NetProfitSummary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * F22.1 謝礼決済 第三陣 C2: リコンシリエーション・バッチ（整合バッチ・純益可視化・設計書 02 §6.3）。
 *
 * <p>2 つのスケジュールでリコンシリエーションを回す。いずれも抽出（read）と実処理（{@link FeeReconciliationService}
 * の {@code REQUIRES_NEW} 単位処理）を分離し、1 件の失敗を個別 try/catch で握りつぶさず継続する（症状を隠さない・
 * CLAUDE.md 根治原則）。多重起動は {@code @SchedulerLock}（ShedLock）で防ぐ。{@code @BatchEndpoint} により
 * F10.X 実機検証基盤から名前で手動起動できる:</p>
 * <ul>
 *   <li><b>15 分間隔</b>（{@link #reconcileEvery15Min()}）: (a) pending 手数料の補完＋(b) 複式検算。
 *       C1 で先送りされた ModeB 実手数料を balance_transaction 確定後に補完し、全記帳取引の借貸一致を検算する。</li>
 *   <li><b>日次</b>（{@link #reconcileNetProfitDaily()}）: (c) 前日 capture 分の {@code application_fee} − Stripe 実手数料＝
 *       Mannschaft 純益を集計し、実手数料を {@code ledger_entries}(FEE) に実額記録、想定（≈0.036）からのブレを可視化する。</li>
 * </ul>
 *
 * <h3>アラート手段（payment 専用アラート基盤が無いための最小構成）</h3>
 * <p>「複式不一致」「pending 滞留」は構造化 {@code log.error}/{@code log.warn} で可視化し、件数を集計してバッチ完了ログに
 * 残す（家老偵察: payment 配下に専用アラート基盤なし）。これにより既存のログ監視で検知でき、症状を隠さない。
 * Micrometer 等のメトリクスは payment ドメインに未導入のため本陣では log を正とし、メトリクス連携は後続に委ねる。</p>
 *
 * <p>設計書: docs/features/F22.1_market/payment/02_api_design.md §6.3</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeeReconciliationBatch {

    private final FeeReconciliationService feeReconciliationService;
    private final Clock clock;

    /**
     * 15 分間隔のリコンシリエーション: (a) pending 手数料の補完＋(b) 複式検算（設計書 02 §6.3）。
     */
    @BatchEndpoint(name = "payment-fee-reconciliation",
            description = "謝礼決済リコンシリ: ModeB 実手数料の pending 補完＋ledger 複式検算（不一致/滞留はログ上申）")
    // initialDelay を設けて起動直後の即時発火を避ける。@Scheduled(fixedDelay) は initialDelay 既定 0 ＝
    // コンテキスト起動と同時に 1 回目が走るため、@SpringBootTest の統合テスト中にリコンシリが暴発し
    // ロック未保持の ShedLock ノイズや未ウォームアップ実行を招く。15 分後の初回＝EscrowLifecycleBatch
    // （fixedDelay=1h で起動中に発火しない）と同じく「テスト実行時間内には発火しない」作法へ揃える。
    @Scheduled(initialDelay = 900_000, fixedDelay = 900_000)
    @SchedulerLock(name = "paymentFeeReconciliation", lockAtLeastFor = "PT2M", lockAtMostFor = "PT30M")
    public void reconcileEvery15Min() {
        LocalDateTime now = LocalDateTime.now(clock);
        log.info("リコンシリ（15分）開始: now={}", now);

        ReconcileResult completion = completePendingRecoveries();
        int imbalances = verifyDoubleEntries();

        log.info("リコンシリ（15分）完了: 補完={}, 滞留(pending)={}, 補完対象外={}, 複式不一致={}",
                completion.completed(), completion.stillPending(), completion.skipped(), imbalances);
    }

    /**
     * 日次のリコンシリエーション: (c) 前日 capture 分の純益突合＋実手数料の台帳化（設計書 02 §6.3）。
     *
     * <p>毎日 03:00（{@code cron}）に前日 00:00〜当日 00:00 の capture 済取引を対象に集計する。</p>
     */
    @BatchEndpoint(name = "payment-net-profit-reconciliation-daily",
            description = "謝礼決済リコンシリ: 前日 capture 分の application_fee−実手数料＝純益を集計し FEE 実額を台帳化")
    @Scheduled(cron = "0 0 3 * * *")
    @SchedulerLock(name = "paymentNetProfitReconciliationDaily", lockAtLeastFor = "PT5M", lockAtMostFor = "PT55M")
    public void reconcileNetProfitDaily() {
        LocalDate today = LocalDate.now(clock);
        LocalDateTime from = today.minusDays(1).atStartOfDay();
        LocalDateTime to = today.atStartOfDay();
        log.info("純益リコンシリ（日次）開始: 対象期間=[{}, {})", from, to);

        NetProfitSummary summary = reconcileNetProfit(from, to);

        log.info("純益リコンシリ（日次）完了: 対象={}件, pending={}件, FEE実額記帳={}件, "
                        + "徴収合計={}, Stripe実手数料合計={}, 純益合計={}, 純益率={}",
                summary.transactionCount(), summary.pendingCount(), summary.feeRecordedCount(),
                summary.totalApplicationFee(), summary.totalStripeFee(), summary.totalNetProfit(),
                String.format(java.util.Locale.ROOT, "%.4f", summary.netProfitRatio()));
    }

    /**
     * 補完待ち候補をループし 1 件ずつ独立トランザクションで補完する（抽出と実処理を分離・個別失敗を継続）。
     *
     * <p>本メソッド自体はトランザクション境界を持たず、{@link FeeReconciliationService#completePendingRecovery}
     * が {@code REQUIRES_NEW} で 1 件ずつ commit する。1 件の Stripe 例外等は握りつぶさず ERROR ログに残し継続する。</p>
     */
    ReconcileResult completePendingRecoveries() {
        List<UUID> candidates = feeReconciliationService.findPendingRecoveryCandidates();
        int completed = 0;
        int stillPending = 0;
        int skipped = 0;
        for (UUID escrowId : candidates) {
            try {
                CompletionOutcome outcome = feeReconciliationService.completePendingRecovery(escrowId);
                switch (outcome) {
                    case COMPLETED -> completed++;
                    case STILL_PENDING -> stillPending++;
                    case SKIPPED -> skipped++;
                }
            } catch (RuntimeException ex) {
                // 1 件の失敗は握りつぶさず ERROR ログに残し、他件の処理は継続する（観測可能化）。
                log.error("pending 補完に失敗（他件は継続）: escrowId={}, reason={}", escrowId, ex.getMessage(), ex);
            }
        }
        if (stillPending > 0) {
            // pending 滞留はアラート対象（balance_transaction が確定しない異常の兆候・症状を隠さない）。
            log.warn("ModeB 実手数料の pending 滞留を検出: 滞留={}件（補完できず次回再試行・要監視・§6.3）", stillPending);
        }
        return new ReconcileResult(completed, stillPending, skipped);
    }

    /**
     * 記帳のある全取引の複式検算をループし、不一致件数を返す（抽出と検算を分離・個別失敗を継続）。
     *
     * @return 借貸不一致だった取引の件数（0 なら全件整合）
     */
    int verifyDoubleEntries() {
        List<UUID> ids = feeReconciliationService.findLedgerTransactionIds();
        int imbalances = 0;
        for (UUID escrowId : ids) {
            try {
                if (!feeReconciliationService.verifyDoubleEntry(escrowId)) {
                    imbalances++;
                }
            } catch (RuntimeException ex) {
                log.error("複式検算に失敗（他件は継続）: escrowId={}, reason={}", escrowId, ex.getMessage(), ex);
            }
        }
        return imbalances;
    }

    /**
     * 期間内 capture 済取引の純益突合をループ集計する（抽出と実処理を分離・個別失敗を継続）。
     *
     * @param from 集計開始（含む）
     * @param to   集計終了（含まない）
     * @return 期間合算の純益サマリ
     */
    NetProfitSummary reconcileNetProfit(LocalDateTime from, LocalDateTime to) {
        List<UUID> ids = feeReconciliationService.findCapturedEscrowIdsForNetProfit(from, to);
        int count = 0;
        int pending = 0;
        int feeRecorded = 0;
        long totalApplicationFee = 0L;
        long totalStripeFee = 0L;
        long totalNetProfit = 0L;
        for (UUID escrowId : ids) {
            try {
                NetProfitOutcome outcome = feeReconciliationService.reconcileNetProfit(escrowId);
                if (outcome.pending()) {
                    pending++;
                    continue;
                }
                if (!outcome.applicable()) {
                    continue;
                }
                count++;
                totalApplicationFee += outcome.applicationFee();
                totalStripeFee += outcome.stripeFee();
                totalNetProfit += outcome.netProfit();
                if (outcome.feeRecorded()) {
                    feeRecorded++;
                }
            } catch (RuntimeException ex) {
                log.error("純益突合に失敗（他件は継続）: escrowId={}, reason={}", escrowId, ex.getMessage(), ex);
            }
        }
        return new NetProfitSummary(count, pending, totalApplicationFee, totalStripeFee, totalNetProfit, feeRecorded);
    }

    /** 補完ループの集計（COMPLETED/STILL_PENDING/SKIPPED の件数）。 */
    record ReconcileResult(int completed, int stillPending, int skipped) {
    }
}
