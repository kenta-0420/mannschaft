package com.mannschaft.app.payment.escrow;

/**
 * F22.1 謝礼決済: 複式記帳の記帳種別。
 *
 * <p>{@code ledger_entries.entry_type}（VARCHAR(24) + CHECK 6値）に対応する。</p>
 */
public enum LedgerEntryType {
    AUTHORIZE,
    CAPTURE,
    TRANSFER_OUT,
    FEE,
    REFUND,
    CANCEL
}
