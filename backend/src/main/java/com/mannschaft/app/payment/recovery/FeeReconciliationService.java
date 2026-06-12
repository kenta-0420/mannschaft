package com.mannschaft.app.payment.recovery;

import com.mannschaft.app.payment.escrow.EscrowStatus;
import com.mannschaft.app.payment.escrow.EscrowTransactionEntity;
import com.mannschaft.app.payment.escrow.EscrowTransactionRepository;
import com.mannschaft.app.payment.escrow.LedgerAccount;
import com.mannschaft.app.payment.escrow.LedgerDirection;
import com.mannschaft.app.payment.escrow.LedgerEntryBuilder;
import com.mannschaft.app.payment.escrow.LedgerEntryEntity;
import com.mannschaft.app.payment.escrow.LedgerEntryRepository;
import com.mannschaft.app.payment.escrow.LedgerEntryType;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * F22.1 謝礼決済 第三陣 C2: リコンシリエーション（整合バッチ・純益可視化）の単位処理（設計書 02 §6.3）。
 *
 * <p>3 つの責務を 1 escrow（または 1 取引）単位の独立トランザクション（{@link Propagation#REQUIRES_NEW}）で提供する。
 * バッチ（{@code FeeReconciliationBatch}）が抽出（read）→ループ→本サービスの単位処理（write）を呼ぶ構造で、
 * 1 件の失敗が他件を巻き込まない（呼び出し側がループで個別 try/catch する前提）:</p>
 * <ol>
 *   <li><b>(a) pending 手数料の補完</b>: C1（{@code ConnectChargeService.recordModeBStripeFeeRecovery}）が
 *       balance_transaction 未確定で計上を先送りした ModeB 返金を拾い、{@link StripePaymentProvider#retrieveChargeProcessingFee}
 *       を再試行→確定したら C1 と同一会計（RECOVERY 仕訳 D PLATFORM_FEE=C PAYEE ＋ {@code outstanding_amount} 計上）で補完する。</li>
 *   <li><b>(b) 複式検算</b>: 1 取引の {@code ledger_entries} 借方合計＝貸方合計を検算し、不一致は握りつぶさず
 *       {@code log.error} で上申する（CLAUDE.md 根治原則）。</li>
 *   <li><b>(c) ledger×Stripe 突合・純益可視化</b>: 日次で対象取引の {@code application_fee_amount}（徴収）と
 *       Stripe 実手数料（{@code balance_transaction.fee}）の差＝Mannschaft 純益を集計し、想定（≈0.036）からのブレを
 *       台帳（FEE 行の実額記録）とログで可視化する（症状を隠さない・README §3.4）。</li>
 * </ol>
 *
 * <h3>補完待ちの識別（marker 列を増やさない導出）</h3>
 * <p>C1 は ModeB 返金で実手数料が確定すれば {@link LedgerEntryType#RECOVERY} 仕訳を残し、未確定ならスキップする。
 * よって「ModeB 返金記帳あり（{@code REFUND/D/PAYER}）かつ {@code RECOVERY} 行なし」が補完待ち候補となる
 * （{@link LedgerEntryRepository#findModeBRefundEscrowsWithoutRecovery}）。balance_transaction が確定し続けない
 * （延々 pending が残る）場合は滞留としてアラートする（(a) の戻り値で滞留を区別）。</p>
 *
 * <p>設計書: docs/features/F22.1_market/payment/02_api_design.md §6.3</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeeReconciliationService {

    /** 純益率の想定中央値（グロスアップ後課金額に対する Stripe 実手数料 ≈3.6%・README §3.4）。 */
    static final double EXPECTED_STRIPE_FEE_RATE = 0.036;

    /** 純益率ブレの警告閾値（想定との相対乖離がこの割合を超えたら台帳ログで可視化）。 */
    static final double FEE_RATE_DEVIATION_ALERT_THRESHOLD = 0.30;

    private final EscrowTransactionRepository escrowTransactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final FeeRecoveryBalanceRepository feeRecoveryBalanceRepository;
    private final StripePaymentProvider stripePaymentProvider;

    // ============================================================
    // (a) pending 手数料の補完
    // ============================================================

    /**
     * C1 で先送りした ModeB 返金の実手数料を、balance_transaction 確定後に補完計上する（§6.3 (a)）。
     *
     * <p>行を新規に作るため AFTER の独立トランザクション（{@link Propagation#REQUIRES_NEW}）で実行する。
     * 1 escrow に複数回の部分 ModeB 返金がありうるため、{@code REFUND/D/PAYER} の Refund ID ごとに
     * C1 と同じ比例計上（{@code round(stripeFee × grossRefund / chargeAmount)}）で RECOVERY 仕訳を追記し、
     * {@code fee_recovery_balances.outstanding_amount} を積む。冪等: 抽出条件が「RECOVERY 行なし」のため、
     * 本メソッドで 1 行でも RECOVERY を立てれば次回以降は候補から外れる（二重計上しない）。</p>
     *
     * @param escrowId 補完待ち候補の escrow ID（ModeB 返金記帳ありかつ RECOVERY なし）
     * @return 補完結果（{@link CompletionOutcome}）。pending 滞留・補完済み・対象外を区別する
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CompletionOutcome completePendingRecovery(UUID escrowId) {
        Optional<EscrowTransactionEntity> found = escrowTransactionRepository.findByIdForUpdate(escrowId);
        if (found.isEmpty()) {
            log.warn("補完対象 escrow が存在しません（処理間に削除/未存在）: escrowId={}", escrowId);
            return CompletionOutcome.SKIPPED;
        }
        EscrowTransactionEntity escrow = found.get();
        String piId = escrow.getStripePaymentIntentId();
        if (piId == null || piId.isBlank()) {
            // PI 未作成では実手数料を取得できない。補完不能として観測可能化（握りつぶさない）。
            log.warn("補完不能: PaymentIntent 未解決のため実手数料取得不能: escrowId={}", escrowId);
            return CompletionOutcome.SKIPPED;
        }

        long stripeFee = stripePaymentProvider.retrieveChargeProcessingFee(piId);
        if (stripeFee == StripePaymentProvider.PROCESSING_FEE_PENDING) {
            // まだ未確定。延々残るなら滞留として呼び出し側がアラート集計する（症状を隠さない）。
            log.warn("ModeB 実手数料が依然 pending（補完できず滞留）: escrowId={}, piId={}", escrowId, piId);
            return CompletionOutcome.STILL_PENDING;
        }

        long chargeAmount = escrow.getAmount();
        List<Object[]> grossByRefund = ledgerEntryRepository.findModeBGrossRefundByRefundId(escrowId);
        if (grossByRefund.isEmpty()) {
            // ModeB 返金記帳が見当たらない（候補抽出後に状態が変わった等）。対象外で no-op。
            log.warn("補完対象に ModeB 返金記帳が見当たりません（抽出後の状態変化）: escrowId={}", escrowId);
            return CompletionOutcome.SKIPPED;
        }

        long totalRecovered = 0L;
        for (Object[] row : grossByRefund) {
            String refundId = (String) row[0];
            long grossRefund = ((Number) row[1]).longValue();
            long recoverable = (chargeAmount <= 0L) ? stripeFee
                    : Math.round((double) stripeFee * grossRefund / chargeAmount);
            if (recoverable <= 0L) {
                continue;
            }
            // C1 と同一の自己完結 RECOVERY 仕訳（D PLATFORM_FEE = C PAYEE = recoverable）。借貸一致は build() が検算。
            List<LedgerEntryEntity> recoveryEntries =
                    LedgerEntryBuilder.forTransaction(escrowId, escrow.getCurrency())
                            .debit(LedgerEntryType.RECOVERY, LedgerAccount.PLATFORM_FEE, recoverable, refundId)
                            .credit(LedgerEntryType.RECOVERY, LedgerAccount.PAYEE, recoverable, refundId)
                            .build();
            ledgerEntryRepository.saveAll(recoveryEntries);
            totalRecovered += recoverable;
        }

        if (totalRecovered <= 0L) {
            // 実手数料は確定したが比例計上が全て 0（極小 charge 等）。RECOVERY 行を立てないと候補に残り続けるため、
            // 確定済みの事実を記録する 0 円 no-op は作れない（記帳は正値必須）。ここは「補完済み相当」として扱い、
            // 滞留と区別する（症状を隠さない・ログで可視化）。
            log.info("ModeB 実手数料は確定したが比例計上額が全て 0（補完不要・対象外）: escrowId={}, stripeFee={}, charge={}",
                    escrowId, stripeFee, chargeAmount);
            return CompletionOutcome.SKIPPED;
        }

        // 未回収残高を payee（connect_account）×currency で upsert（C1 と同一・organization_id も埋める）。
        String currency = normalizeRecoveryCurrency(escrow.getCurrency());
        FeeRecoveryBalanceEntity balance = feeRecoveryBalanceRepository
                .findByConnectAccountIdAndCurrencyAndDeletedAtIsNull(escrow.getPayeeConnectAccountId(), currency)
                .orElseGet(() -> FeeRecoveryBalanceEntity.builder()
                        .connectAccountId(escrow.getPayeeConnectAccountId())
                        .organizationId(escrow.getOrganizationId())
                        .currency(currency)
                        .outstandingAmount(0L)
                        .build());
        long current = balance.getOutstandingAmount() != null ? balance.getOutstandingAmount() : 0L;
        balance.setOutstandingAmount(current + totalRecovered);
        if (balance.getOrganizationId() == null && escrow.getOrganizationId() != null) {
            balance.setOrganizationId(escrow.getOrganizationId());
        }
        feeRecoveryBalanceRepository.save(balance);

        log.info("ModeB 実手数料を補完計上（§6.3 C2 リコンシリ）: escrowId={}, payeeAccountId={}, currency={}, "
                        + "stripeFee={}, charge={}, 補完計上額={}, 計上後残高={}",
                escrowId, escrow.getPayeeConnectAccountId(), currency,
                stripeFee, chargeAmount, totalRecovered, balance.getOutstandingAmount());
        return CompletionOutcome.COMPLETED;
    }

    // ============================================================
    // (b) 複式検算
    // ============================================================

    /**
     * 1 取引の {@code ledger_entries} 借方合計＝貸方合計を検算する（§6.3 (b)・01 §3.3）。
     *
     * <p>読み取り専用（{@link Propagation#REQUIRES_NEW} 不要・書込なし）。不一致は握りつぶさず
     * {@code log.error} で上申し {@code false} を返す（呼び出し側がアラートカウンタを上げる）。一致なら {@code true}。
     * {@link LedgerEntryBuilder} が記帳時に借貸一致を検算済みだが、複数記帳バッチをまたいだ累積整合
     * （例: RECOVERY 補完後の全体）を独立に再検算することで、記帳経路の取りこぼし・手作業修正を検出する。</p>
     *
     * @param escrowId 検算対象 escrow ID
     * @return 借方合計＝貸方合計なら true（不一致なら false・log.error 済み）
     */
    @Transactional(readOnly = true)
    public boolean verifyDoubleEntry(UUID escrowId) {
        List<LedgerEntryEntity> entries =
                ledgerEntryRepository.findByEscrowTransactionIdOrderByCreatedAtAsc(escrowId);
        if (entries.isEmpty()) {
            return true;
        }
        long debit = 0L;
        long credit = 0L;
        for (LedgerEntryEntity e : entries) {
            if (e.getDirection() == LedgerDirection.D) {
                debit += e.getAmount();
            } else {
                credit += e.getAmount();
            }
        }
        if (debit != credit) {
            // 症状を隠さない: 複式不一致は会計破綻の兆候。握りつぶさず上申する（CLAUDE.md 根治原則）。
            log.error("複式記帳の貸借不一致を検出（要調査・§6.3 リコンシリ）: escrowId={}, 借方={}, 貸方={}, 差額={}",
                    escrowId, debit, credit, debit - credit);
            return false;
        }
        return true;
    }

    // ============================================================
    // (c) ledger×Stripe 突合・純益可視化
    // ============================================================

    /**
     * 1 取引の純益（{@code application_fee_amount} − Stripe 実手数料）を求め、実手数料を {@code ledger_entries}(FEE) に
     * 実額記録し（現状は設定値のみ）、想定（≈0.036）からのブレを台帳・ログで可視化する（§6.3 (c)・README §3.4）。
     *
     * <p>行を追記するため独立トランザクション（{@link Propagation#REQUIRES_NEW}）で実行する。実手数料は
     * {@link StripePaymentProvider#retrieveChargeProcessingFee} で取得し、未確定なら計上をスキップ（pending は (a) で補完）。
     * 純益（{@code application_fee − stripeFee}）を返す。実手数料の対課金額率が想定から大きくブレた場合は
     * {@code log.warn} で可視化する（症状を隠さない）。FEE 実額記帳は自己完結仕訳
     * （{@code D PLATFORM_FEE = C PAYEE = stripeFee}）で借貸一致させ、既存記帳に影響しない。
     * 同一取引の二重 FEE 記帳を避けるため、{@code FEE/RECOVERY} の実額記帳が既にあればスキップする。</p>
     *
     * @param escrowId 集計対象 escrow ID（CAPTURED 以降・PI 解決済）
     * @return 純益突合結果（{@link NetProfitOutcome}）。集計値・スキップ・pending を区別する
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public NetProfitOutcome reconcileNetProfit(UUID escrowId) {
        Optional<EscrowTransactionEntity> found = escrowTransactionRepository.findById(escrowId);
        if (found.isEmpty()) {
            return NetProfitOutcome.ofSkipped();
        }
        EscrowTransactionEntity escrow = found.get();
        String piId = escrow.getStripePaymentIntentId();
        if (piId == null || piId.isBlank()) {
            return NetProfitOutcome.ofSkipped();
        }

        long stripeFee = stripePaymentProvider.retrieveChargeProcessingFee(piId);
        if (stripeFee == StripePaymentProvider.PROCESSING_FEE_PENDING) {
            // 実手数料未確定。純益は確定できない。pending として可視化し、後続バッチで再試行（握りつぶさない）。
            log.warn("純益突合スキップ: 実手数料 pending（後続再試行）: escrowId={}, piId={}", escrowId, piId);
            return NetProfitOutcome.ofPending();
        }

        long applicationFee = escrow.getApplicationFeeAmount() != null ? escrow.getApplicationFeeAmount() : 0L;
        long netProfit = applicationFee - stripeFee;

        // 実手数料の対課金額率が想定（≈0.036）から大きくブレたら台帳ログで可視化する（症状を隠さない）。
        long chargeAmount = escrow.getAmount();
        if (chargeAmount > 0L) {
            double actualRate = (double) stripeFee / chargeAmount;
            double deviation = Math.abs(actualRate - EXPECTED_STRIPE_FEE_RATE) / EXPECTED_STRIPE_FEE_RATE;
            if (deviation > FEE_RATE_DEVIATION_ALERT_THRESHOLD) {
                log.warn("Stripe 実手数料率が想定から乖離（要監視・§6.3 (c)）: escrowId={}, 実率={}, 想定={}, 乖離={}",
                        escrowId, String.format(Locale.ROOT, "%.4f", actualRate),
                        EXPECTED_STRIPE_FEE_RATE, String.format(Locale.ROOT, "%.2f", deviation));
            }
        }

        // 実手数料の実額を ledger(FEE) に自己完結仕訳で記帳（現状は設定値のみ→実額の台帳化）。二重記帳は冪等にスキップ。
        boolean recorded = false;
        if (stripeFee > 0L && !hasStripeFeeLedgerRecorded(escrowId, piId)) {
            List<LedgerEntryEntity> feeEntries =
                    LedgerEntryBuilder.forTransaction(escrowId, escrow.getCurrency())
                            .debit(LedgerEntryType.FEE, LedgerAccount.PLATFORM_FEE, stripeFee, piId)
                            .credit(LedgerEntryType.FEE, LedgerAccount.PAYEE, stripeFee, piId)
                            .build();
            ledgerEntryRepository.saveAll(feeEntries);
            recorded = true;
        }

        log.info("純益突合（§6.3 (c)）: escrowId={}, application_fee={}, stripeFee={}, 純益={}, FEE実額記帳={}",
                escrowId, applicationFee, stripeFee, netProfit, recorded);
        return NetProfitOutcome.of(applicationFee, stripeFee, netProfit, recorded);
    }

    /**
     * 当該取引の Stripe 実手数料（FEE）が既に台帳記帳済みかを判定する（二重 FEE 記帳の冪等ガード）。
     *
     * <p>本サービスの FEE 記帳は {@code stripe_object_id=piId} の {@code FEE/D/PLATFORM_FEE} 行を立てる。
     * 同一取引・同一 PI で既に立っていれば再記帳しない（リコンシリの複数回実行で FEE が増殖しない）。</p>
     */
    private boolean hasStripeFeeLedgerRecorded(UUID escrowId, String piId) {
        return ledgerEntryRepository.findByEscrowTransactionIdOrderByCreatedAtAsc(escrowId).stream()
                .anyMatch(e -> e.getEntryType() == LedgerEntryType.FEE
                        && e.getDirection() == LedgerDirection.D
                        && e.getAccount() == LedgerAccount.PLATFORM_FEE
                        && piId.equals(e.getStripeObjectId()));
    }

    // ============================================================
    // 抽出（read・バッチが呼ぶ）
    // ============================================================

    /** 補完待ち候補（ModeB 返金記帳ありかつ RECOVERY なし）の escrow ID を返す（§6.3 (a)）。 */
    @Transactional(readOnly = true)
    public List<UUID> findPendingRecoveryCandidates() {
        return ledgerEntryRepository.findModeBRefundEscrowsWithoutRecovery();
    }

    /** 複式検算の母集合（記帳のある全取引）の escrow ID を返す（§6.3 (b)）。 */
    @Transactional(readOnly = true)
    public List<UUID> findLedgerTransactionIds() {
        return ledgerEntryRepository.findDistinctEscrowTransactionIds();
    }

    /** 日次純益突合の対象（期間内に capture 済の取引）の escrow ID を返す（§6.3 (c)）。 */
    @Transactional(readOnly = true)
    public List<UUID> findCapturedEscrowIdsForNetProfit(LocalDateTime from, LocalDateTime to) {
        return escrowTransactionRepository
                .findByStatusAndCapturedAtGreaterThanEqualAndCapturedAtLessThan(EscrowStatus.CAPTURED, from, to)
                .stream()
                .map(EscrowTransactionEntity::getId)
                .toList();
    }

    private String normalizeRecoveryCurrency(String currency) {
        return (currency == null || currency.isBlank()) ? "jpy" : currency.toLowerCase(Locale.ROOT);
    }

    /** 補完（§6.3 (a)）の結果。滞留（STILL_PENDING）を補完済み/対象外と区別し、滞留アラートに使う。 */
    public enum CompletionOutcome {
        /** 実手数料が確定し RECOVERY 仕訳＋残高計上で補完した。 */
        COMPLETED,
        /** balance_transaction が依然 pending で補完できなかった（滞留＝アラート対象）。 */
        STILL_PENDING,
        /** 対象外（PI 未解決・記帳消失・比例計上 0 等）で no-op。 */
        SKIPPED
    }

    /**
     * 純益突合（§6.3 (c)）の 1 取引結果。
     *
     * @param applicable     集計に算入できたか（pending/skip でない）
     * @param pending        実手数料 pending で確定できなかったか
     * @param applicationFee 徴収した application_fee（minor）
     * @param stripeFee      Stripe 実手数料（minor・確定値）
     * @param netProfit      純益（applicationFee − stripeFee・minor）
     * @param feeRecorded    実手数料を ledger(FEE) に新規記帳したか
     */
    public record NetProfitOutcome(
            boolean applicable, boolean pending,
            long applicationFee, long stripeFee, long netProfit, boolean feeRecorded) {

        static NetProfitOutcome of(long applicationFee, long stripeFee, long netProfit, boolean feeRecorded) {
            return new NetProfitOutcome(true, false, applicationFee, stripeFee, netProfit, feeRecorded);
        }

        static NetProfitOutcome ofPending() {
            return new NetProfitOutcome(false, true, 0L, 0L, 0L, false);
        }

        static NetProfitOutcome ofSkipped() {
            return new NetProfitOutcome(false, false, 0L, 0L, 0L, false);
        }
    }

    /** 日次純益突合の集計結果（バッチが期間合算してログに可視化する）。 */
    public record NetProfitSummary(
            int transactionCount, int pendingCount,
            long totalApplicationFee, long totalStripeFee, long totalNetProfit, int feeRecordedCount) {

        /** 徴収手数料に対する純益割合（純益 / 徴収 application_fee）。日次ログで可視化する補助値。 */
        public double netProfitRatio() {
            return totalApplicationFee == 0L ? 0.0 : (double) totalNetProfit / totalApplicationFee;
        }
    }
}
