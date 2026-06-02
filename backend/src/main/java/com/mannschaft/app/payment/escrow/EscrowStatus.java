package com.mannschaft.app.payment.escrow;

/**
 * F22.1 謝礼決済: エスクロー取引の状態。
 *
 * <p>{@code escrow_transactions.status}（VARCHAR(20) + CHECK 7値）に対応する。
 * 設計書 §3.2 の状態遷移を参照。</p>
 */
public enum EscrowStatus {
    /** 与信済（資金未移動・エスクロー保持中）。 */
    AUTHORIZED,
    /** 払出保留（受領者 onboarding 未完了で capture 待ち）。 */
    HELD,
    /** capture 済（払出確定・受領者へ transfer 完了）。 */
    CAPTURED,
    /** 部分返金済。 */
    PARTIALLY_REFUNDED,
    /** 全額返金済。 */
    REFUNDED,
    /** 与信取消（capture 前の札下げ/期限切れ/hold 失効）。 */
    CANCELLED,
    /** 係争中。 */
    DISPUTED
}
