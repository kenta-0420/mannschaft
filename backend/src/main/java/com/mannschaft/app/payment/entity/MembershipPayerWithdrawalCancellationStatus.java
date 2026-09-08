package com.mannschaft.app.payment.entity;

/**
 * 柱③-B（CMP-260901-1538）PR-3: 払い手退会に伴う継続課金の期末解約の処理状態
 * （{@code membership_payer_withdrawal_cancellations.status}）。
 *
 * <p>Stripe（外部）と DB（自前）の2箇所を跨いで更新するため、両者が確定するまでは
 * {@link #PENDING} のまま残す。プロセスが落ちても行は残るので、PR-4 の夜次バッチが
 * {@link #PENDING}/{@link #FAILED} を機械的に拾い直せる（Codex 検分1巡目 P1-1）。</p>
 */
public enum MembershipPayerWithdrawalCancellationStatus {

    /** 予約に着手したが Stripe・DB の双方の確定までは至っていない（プロセス停止・Stripe 応答不明も含む）。 */
    PENDING,

    /** Stripe の {@code cancel_at_period_end=true} と DB 反映の双方が確定した。 */
    SUCCEEDED,

    /** 明示的に失敗した（{@code last_error} に理由を残す）。再試行対象。 */
    FAILED
}
