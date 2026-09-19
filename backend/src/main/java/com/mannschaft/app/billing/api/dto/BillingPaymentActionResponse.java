package com.mannschaft.app.billing.api.dto;

import java.time.Instant;

/**
 * Billing Center PR6b-1 C群: 3DS payment-action の応答（AC-48）。
 *
 * <p>{@code GET …/changes/{changeId}/payment-action} が 200 のときだけ返す。
 * {@code clientSecret} は Stripe から都度取得した短命な秘密であり、DB には一切保存しない
 * （{@link com.mannschaft.app.billing.BillingPlanChangeGateway#retrievePaymentAction}）。</p>
 *
 * @param paymentAction 追加認証の情報
 */
public record BillingPaymentActionResponse(PaymentActionDto paymentAction) {

    /**
     * @param type         追加認証の種別（{@code payment_intent} 等）
     * @param clientSecret Stripe の client secret（都度取得・保存禁止）
     * @param expiresAt    追加認証の期限
     */
    public record PaymentActionDto(String type, String clientSecret, Instant expiresAt) {
    }
}
