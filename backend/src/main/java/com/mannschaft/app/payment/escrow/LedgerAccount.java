package com.mannschaft.app.payment.escrow;

/**
 * F22.1 謝礼決済: 複式記帳の勘定（相手勘定）。
 *
 * <p>{@code ledger_entries.account}（VARCHAR(16)）に対応する。</p>
 */
public enum LedgerAccount {
    ESCROW,
    PAYEE,
    PLATFORM_FEE,
    PAYER
}
