package com.mannschaft.app.payment.escrow;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * F22.1 謝礼決済: 複式記帳の記帳ヘルパ（骨格）。
 *
 * <p>P2-a では実記帳（DB への行作成）は走らせない。本クラスは P2-b/c で実際に
 * {@code ledger_entries} へ追記する際の「借方合計＝貸方合計」不変条件を一箇所に集約し、
 * 単体テストで固定するための土台である（設計書 01 §3.3）。</p>
 *
 * <p>不変条件: 1 取引（1 回の記帳バッチ）の借方（{@link LedgerDirection#D}）合計と
 * 貸方（{@link LedgerDirection#C}）合計は必ず一致する。{@link #build} は不一致を検知すると
 * 例外を投げ、症状を隠さない（CLAUDE.md 根治原則）。</p>
 */
public final class LedgerEntryBuilder {

    private final UUID escrowTransactionId;
    private final String currency;
    private final List<LedgerEntryEntity> entries = new ArrayList<>();

    private LedgerEntryBuilder(UUID escrowTransactionId, String currency) {
        if (escrowTransactionId == null) {
            throw new IllegalArgumentException("escrowTransactionId は必須です");
        }
        this.escrowTransactionId = escrowTransactionId;
        this.currency = currency != null ? currency : "JPY";
    }

    /**
     * 指定取引に対する記帳ビルダを生成する。
     *
     * @param escrowTransactionId エスクロー取引 ID
     * @param currency            通貨（null の場合 {@code JPY}）
     * @return ビルダ
     */
    public static LedgerEntryBuilder forTransaction(UUID escrowTransactionId, String currency) {
        return new LedgerEntryBuilder(escrowTransactionId, currency);
    }

    /**
     * 借方（Debit）行を追加する。
     */
    public LedgerEntryBuilder debit(LedgerEntryType entryType, LedgerAccount account,
                                    long amount, String stripeObjectId) {
        return add(LedgerDirection.D, entryType, account, amount, stripeObjectId);
    }

    /**
     * 貸方（Credit）行を追加する。
     */
    public LedgerEntryBuilder credit(LedgerEntryType entryType, LedgerAccount account,
                                     long amount, String stripeObjectId) {
        return add(LedgerDirection.C, entryType, account, amount, stripeObjectId);
    }

    /**
     * RECOVERY 仕訳の借貸ペア（{@code debit} → {@code credit}）に経路識別（{@link RecoveryKind}）を焼き付けて追加する
     * （§6.3 検分🔴根治）。
     *
     * <p>RECOVERY×PAYEE は勘定の向きだけでは C1/C2 発生計上と A 回収実行/再計上を峻別できず、自己返金時に純額計算へ
     * 混入して回収金が消失する。各 RECOVERY 行に {@code recovery_kind} を持たせ、A 経路だけの純額導出を可能にする。
     * 借貸の {@link LedgerAccount} は呼び出し側が経路ごとに正しく指定する（C1/C2/再計上＝{@code D PLATFORM_FEE / C PAYEE}・
     * 回収実行＝{@code D PAYEE / C PLATFORM_FEE}）。</p>
     *
     * @param kind         RECOVERY 経路識別
     * @param debitAccount 借方勘定
     * @param creditAccount 貸方勘定
     * @param amount       金額（minor・正値）
     * @param stripeObjectId 突合用 ID（{@code re_xxx}/{@code pi_xxx}/{@code cancel-<id>}）
     * @return このビルダ
     */
    public LedgerEntryBuilder recoveryPair(RecoveryKind kind, LedgerAccount debitAccount,
                                           LedgerAccount creditAccount, long amount, String stripeObjectId) {
        add(LedgerDirection.D, LedgerEntryType.RECOVERY, debitAccount, amount, stripeObjectId, kind);
        add(LedgerDirection.C, LedgerEntryType.RECOVERY, creditAccount, amount, stripeObjectId, kind);
        return this;
    }

    private LedgerEntryBuilder add(LedgerDirection direction, LedgerEntryType entryType,
                                   LedgerAccount account, long amount, String stripeObjectId) {
        return add(direction, entryType, account, amount, stripeObjectId, null);
    }

    private LedgerEntryBuilder add(LedgerDirection direction, LedgerEntryType entryType,
                                   LedgerAccount account, long amount, String stripeObjectId,
                                   RecoveryKind recoveryKind) {
        if (amount <= 0) {
            throw new IllegalArgumentException("記帳金額は正の整数（最小通貨単位）でなければなりません: " + amount);
        }
        // running_balance は 借方=+ / 貸方=- の符号付き累積（整合検算用・01 §3.3）
        long signed = direction == LedgerDirection.D ? amount : -amount;
        long runningBalance = currentRunningBalance() + signed;
        entries.add(LedgerEntryEntity.builder()
                .escrowTransactionId(escrowTransactionId)
                .entryType(entryType)
                .account(account)
                .direction(direction)
                .amount(amount)
                .currency(currency)
                .runningBalance(runningBalance)
                .stripeObjectId(stripeObjectId)
                .recoveryKind(recoveryKind)
                .build());
        return this;
    }

    private long currentRunningBalance() {
        return entries.isEmpty() ? 0L : entries.get(entries.size() - 1).getRunningBalance();
    }

    /** 借方合計。 */
    public long totalDebit() {
        return entries.stream()
                .filter(e -> e.getDirection() == LedgerDirection.D)
                .mapToLong(LedgerEntryEntity::getAmount)
                .sum();
    }

    /** 貸方合計。 */
    public long totalCredit() {
        return entries.stream()
                .filter(e -> e.getDirection() == LedgerDirection.C)
                .mapToLong(LedgerEntryEntity::getAmount)
                .sum();
    }

    /**
     * 借方合計＝貸方合計の不変条件を検証し、記帳行リストを確定する。
     *
     * @return 検証済みの記帳行（不変条件成立）
     * @throws IllegalStateException 借方合計≠貸方合計（症状を隠さず即時失敗）
     */
    public List<LedgerEntryEntity> build() {
        if (entries.isEmpty()) {
            throw new IllegalStateException("記帳行が 1 件もありません");
        }
        long debit = totalDebit();
        long credit = totalCredit();
        if (debit != credit) {
            throw new IllegalStateException(
                    "複式記帳の貸借不一致: 借方=" + debit + " 貸方=" + credit
                            + "（escrowTransactionId=" + escrowTransactionId + "）");
        }
        return List.copyOf(entries);
    }
}
