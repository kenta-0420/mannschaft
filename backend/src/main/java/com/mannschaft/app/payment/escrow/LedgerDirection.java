package com.mannschaft.app.payment.escrow;

/**
 * F22.1 謝礼決済: 複式記帳の借方/貸方。
 *
 * <p>{@code ledger_entries.direction}（CHAR(1) + CHECK）に対応する。
 * {@code @Enumerated(STRING)} で enum 名（"D"/"C"）がそのまま CHAR(1) に格納される。</p>
 */
public enum LedgerDirection {
    /** 借方 Debit。 */
    D,
    /** 貸方 Credit。 */
    C
}
