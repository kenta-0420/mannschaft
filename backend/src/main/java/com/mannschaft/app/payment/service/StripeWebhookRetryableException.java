package com.mannschaft.app.payment.service;

/**
 * F08.9 P5 第三波: platform Webhook 処理の<b>再送要求</b>を表す専用例外（設計書 02 §4.2）。
 *
 * <p><b>なぜ専用例外か（platform Controller の握り潰しを回避しつつ既存挙動を壊さない）:</b>
 * 既存の {@code StripeWebhookController#handleWebhook}（platform）は F08.2 の設計で<b>全例外を握って 200 を返す</b>
 * （Stripe 再送ストームを避ける）。しかし継続課金の {@code invoice.created} 固定手数料上書き失敗は、draft 窓
 * （約1時間）の間に Stripe の at-least-once 再送（指数バックオフ）でリカバリさせるのが正道であり、握り潰すと
 * 「率手数料のまま finalize→pay されて手数料が崩れる」損失が確定してしまう（症状を隠さない・根治原則）。</p>
 *
 * <p>そこで、<b>再送させたい失敗だけ</b>を本例外で表し、platform Controller は本例外型のみ握らずに再送出する
 * （Spring が 500 を返し Stripe が再送）。F08.2 の既存イベント（checkout 等）の予期せぬ例外は従来どおり 200 で
 * 握る挙動を維持し、他処理への影響を出さない。{@code invoice.paid} 等の記帳系の失敗も、二重起票の害が無く再送で
 * 回復すべきため本例外で再送させる（冪等ゲートが FAILED を再処理可と判定する）。</p>
 */
public class StripeWebhookRetryableException extends RuntimeException {

    public StripeWebhookRetryableException(String message) {
        super(message);
    }

    public StripeWebhookRetryableException(String message, Throwable cause) {
        super(message, cause);
    }
}
