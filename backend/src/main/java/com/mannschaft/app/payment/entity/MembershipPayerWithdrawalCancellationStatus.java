package com.mannschaft.app.payment.entity;

import java.util.List;

/**
 * 柱③-B（CMP-260901-1538）PR-3: 払い手退会に伴う継続課金の期末解約の処理状態
 * （{@code membership_payer_withdrawal_cancellations.status}）。
 *
 * <p>Stripe（外部）と DB（自前）の2箇所を跨いで更新するため、両者が確定するまでは<b>非終端</b>のまま残す。
 * プロセスが落ちても行は残るので、PR-4 の夜次バッチが {@link #NON_TERMINAL} を機械的に拾い直せる
 * （Codex 検分1巡目 P1-1・2巡目 P1-3）。</p>
 *
 * <h2>状態遷移</h2>
 * <pre>
 *   （作成）→ PENDING ──Stripe+DB 確定──→ SUCCEEDED ──退会取消──→ RESTORING ──Stripe+DB 確定──→ RESTORED
 *                │                            │                         │
 *                └──失敗──→ FAILED            └──本人の明示操作──→ SUPERSEDED ←──────┘
 * </pre>
 */
public enum MembershipPayerWithdrawalCancellationStatus {

    /** 予約に着手したが Stripe・DB の双方の確定までは至っていない（プロセス停止・Stripe 応答不明も含む）。 */
    PENDING,

    /** Stripe の {@code cancel_at_period_end=true} と DB 反映の双方が確定した。 */
    SUCCEEDED,

    /** 明示的に失敗した（{@code last_error} に理由を残す）。再試行対象。 */
    FAILED,

    /**
     * 退会取消による復旧に着手したが、Stripe・DB の双方の確定までは至っていない。
     *
     * <p>この状態が必要な理由（Codex 検分2巡目 P1-3）: Stripe の予約解除に成功した直後・DB 反映前に
     * 落ちると、<b>Stripe は継続・DB は {@code cancel_at_period_end=true}</b> という永続的な不整合になる。
     * {@code SUCCEEDED} のままでは再試行対象（非終端）に入らず、退会取消イベントも再配送されないため
     * 自動回復できなかった。復旧の着手を状態として刻むことで照合・再試行の対象になる。</p>
     */
    RESTORING,

    /** 退会取消による復旧が Stripe・DB の双方で確定した（終端）。 */
    RESTORED,

    /**
     * 本人の明示的な操作（自分での期末解約予約・その解除）により、この行が示す「退会処理由来」という
     * 由来が上書きされた（終端・復旧対象外）。
     *
     * <p>この状態が必要な理由（Codex 検分2巡目 P1-1）: 退会取消の復旧処理は
     * {@code cancel_at_period_end=true} しか見ないため、<b>退会取消の後に本人が改めて明示解約した</b>場合に
     * その新しい意思まで解除してしまう。人が新しい判断をした時点で由来の記録を無効化する。</p>
     */
    SUPERSEDED;

    /**
     * <b>非終端</b>＝ Stripe と DB の一致が未確認であり、再試行・照合の対象になる状態。
     *
     * <p>PR-4 の夜次バッチはこの集合を走査する。{@code SUCCEEDED}/{@code RESTORED}/{@code SUPERSEDED} は
     * いずれも「両者が一致している」か「もう触ってはいけない」ため対象外。</p>
     */
    public static final List<MembershipPayerWithdrawalCancellationStatus> NON_TERMINAL =
            List.of(PENDING, FAILED, RESTORING);
}
