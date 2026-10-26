package com.mannschaft.app.payment.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * F08.9 P5 第二波: SetupIntent 作成レスポンス（設計書 02 §4.1）。
 *
 * <p>{@code clientSecret} は払い手本人のみへ返す（他人へ漏らさない・03 §1）。FE が Stripe.js で confirm する。</p>
 */
@Getter
@Builder
public class SetupIntentResponse {

    /** SetupIntent ID（{@code seti_xxx}）。 */
    private final String setupIntentId;

    /** confirm 用 client secret（払い手本人のみへ返す）。 */
    private final String clientSecret;

    /** SetupIntent の状態（{@code requires_payment_method} 等）。 */
    private final String status;
}
