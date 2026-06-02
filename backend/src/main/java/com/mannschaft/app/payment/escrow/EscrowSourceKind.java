package com.mannschaft.app.payment.escrow;

/**
 * F22.1 謝礼決済: エスクロー取引の出所種別（転用点）。
 *
 * <p>{@code escrow_transactions.source_kind}（VARCHAR(12) + CHECK）に対応する。
 * Phase 2 後半が接続するのは RECRUITMENT のみ。他は値だけ確保。</p>
 */
public enum EscrowSourceKind {
    /** 市（募集型予約）。 */
    RECRUITMENT,
    /** F13.1 ジョブマッチング（将来）。 */
    JOBMATCHING,
    /** フリマ（将来）。 */
    FLEAMARKET
}
