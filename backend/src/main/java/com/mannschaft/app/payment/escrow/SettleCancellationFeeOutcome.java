package com.mannschaft.app.payment.escrow;

/**
 * F03.11.1 募集キャンセル料の徴収結果の種別（設計書 §3.4）。
 *
 * <p>recruitment ドメインはこの値だけを見てキャンセル記録の状態を決める。
 * {@link EscrowStatus} を越境させないための境界の型である。</p>
 */
public enum SettleCancellationFeeOutcome {

    /** 与信（AUTHORIZED）からキャンセル料の額だけ部分キャプチャして確定した（§3.2）。 */
    CAPTURED_PARTIAL,

    /** 確定済み（CAPTURED）の取引から差額を返金して徴収した（§3.2・§3.5.4）。 */
    REFUNDED_DIFFERENCE,

    /** 既に徴収済み／返金済みで何もしなかった（冪等・§7.2）。 */
    NO_OP,

    /** 構造的に徴収できない（与信が無い・使えない・§6.3）。 */
    NOT_COLLECTIBLE
}
