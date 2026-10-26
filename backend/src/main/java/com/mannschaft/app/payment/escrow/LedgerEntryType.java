package com.mannschaft.app.payment.escrow;

/**
 * F22.1 謝礼決済: 複式記帳の記帳種別。
 *
 * <p>{@code ledger_entries.entry_type}（VARCHAR(24) + CHECK 7値）に対応する。</p>
 */
public enum LedgerEntryType {
    AUTHORIZE,
    CAPTURE,
    TRANSFER_OUT,
    FEE,
    REFUND,
    CANCEL,
    /**
     * ModeB 返金で Mannschaft が一時負担した Stripe 実手数料を、
     * 後続決済の fee と相殺して自動回収した事実を記帳する種別（§6.3・V84.002 で CHECK 追加）。
     */
    RECOVERY
}
