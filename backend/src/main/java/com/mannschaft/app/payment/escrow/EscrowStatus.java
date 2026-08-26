package com.mannschaft.app.payment.escrow;

/**
 * F22.1 謝礼決済: エスクロー取引の状態。
 *
 * <p>{@code escrow_transactions.status}（VARCHAR(20) + CHECK 8値）に対応する。
 * 設計書 §3.2 の状態遷移を参照。</p>
 *
 * <p><b>第一陣 status 意味論の根治（2026-06-10・マスター裁可）:</b> manual-capture PaymentIntent は
 * 札主（支払者）が Stripe.js で confirm するまで<b>真の与信（カード上のホールド）は立たない</b>。
 * よって PI 作成直後に {@link #AUTHORIZED}（=与信確定済）へ進めるのは意味論的に誤りであり、
 * capture が未確認 PI で失敗する温床だった。これを根治するため、PI 作成済だが札主未 confirm の
 * 中間状態として {@link #PENDING_CONFIRMATION} を新設する。真の与信確定（capture 可能）への昇格は
 * Stripe の {@code payment_intent.amount_capturable_updated} webhook 受信時のみ行う（webhook 意味論修正）。</p>
 */
public enum EscrowStatus {
    /**
     * 与信前段: manual-capture PaymentIntent 作成済だが札主（支払者）が未だ Stripe.js で confirm していない。
     *
     * <p>この状態では Stripe 上に真の与信（amount_capturable）は立っておらず、capture を呼んでも失敗する。
     * 札主が confirm し {@code payment_intent.amount_capturable_updated} が届いた時点で {@link #AUTHORIZED}
     * へ昇格する。confirm 前の cancel/失敗は {@link #CANCELLED}。エスクローモード（RECRUITMENT・MANUAL）専用。</p>
     */
    PENDING_CONFIRMATION,
    /**
     * 完了時即時払い予定（成立〜役務日が7日超で escrow 与信を立てない・第三陣-b「7日超 fallback」・マスター裁可）。
     *
     * <p>カード与信は Stripe 仕様で約7日で失効する。よって成立〜役務完了が7日を超える謝礼は、成立時に
     * 与信（manual-capture PaymentIntent）を立てると役務完了前に失効してしまう。これを避けるため、成立時には
     * 与信せず本状態で escrow を起票し（PaymentIntent 未作成・{@link EscrowCaptureMode#AUTOMATIC}）、最終認証
     * （役務完了）時に<b>即時払い</b>（会費 F08.9 と同型の destination charge・即 capture）へフォールバックする。</p>
     *
     * <p>最終認証で即時 charge を起こすと AUTOMATIC の PaymentIntent を作成して {@link #AUTHORIZED}
     * へ遷移し（{@code hold_expires_at=NULL}・第三陣バッチ非干渉のため意図的に PENDING_CONFIRMATION ではなく
     * AUTHORIZED にする・札主に clientSecret を返す＝第二陣の決済確認 EP を再利用）、札主が Stripe.js で confirm して
     * {@code payment_intent.succeeded} が届くと {@link #CAPTURED} へ確定する。confirm 前の cancel/失効は
     * {@link #CANCELLED}。エスクローモードの謝礼（RECRUITMENT）専用で、7日以内は従来どおり与信（MANUAL）を立てる。</p>
     */
    DEFERRED,
    /** 与信済（資金未移動・エスクロー保持中・真の与信確定＝capture 可能）。 */
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
