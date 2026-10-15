package com.mannschaft.app.payment.escrow;

/**
 * F22.1 謝礼決済: エスクロー取引の出所種別（転用点）。
 *
 * <p>{@code escrow_transactions.source_kind}（VARCHAR(12) + CHECK）に対応する。
 * P2-b/P2-c が接続するのは RECRUITMENT（エスクローモード）、P2-e が MEMBERSHIP（即時モード）。
 * JOBMATCHING/FLEAMARKET は値だけ確保（転用点）。</p>
 */
public enum EscrowSourceKind {
    /** 市（募集型予約）の謝礼・エスクローモード。 */
    RECRUITMENT,
    /** 会費（F08.2）・即時モード（P2-e・設計A）。 */
    MEMBERSHIP,
    /** F13.1 ジョブマッチング（将来）。 */
    JOBMATCHING,
    /** フリマ（将来）。 */
    FLEAMARKET
}
