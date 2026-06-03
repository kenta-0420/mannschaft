package com.mannschaft.app.payment.stripe;

/**
 * F22.1 統一決済 P2-b: Destination PaymentIntent の capture 方式（設計書 02 §0 / §8）。
 *
 * <p>{@link StripePaymentProvider#createDestinationPaymentIntent} に渡し、Stripe の
 * {@code capture_method} へマッピングする。エスクローモード（謝礼）は {@link #MANUAL}、
 * 即時モード（会費）は {@link #AUTOMATIC}。</p>
 */
public enum CaptureMethod {
    /** 手動 capture（与信のみ作成・後で capture）。Stripe {@code capture_method=manual}。 */
    MANUAL,
    /** 自動 capture（作成と同時に確定）。Stripe {@code capture_method=automatic}。 */
    AUTOMATIC
}
