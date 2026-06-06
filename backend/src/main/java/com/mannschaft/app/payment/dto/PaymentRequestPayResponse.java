package com.mannschaft.app.payment.dto;

import com.mannschaft.app.payment.service.PaymentRequestPayResult;
import lombok.Builder;

import java.util.UUID;

/**
 * F08.9 P7: 協会請求支払いのレスポンス DTO（POST /teams/{teamId}/payment-requests/{id}/pay・02_api §7）。
 *
 * <p>払い手本人（チーム ADMIN）が Stripe.js で confirm するための {@code clientSecret} を含む（PCI SAQ-A）。
 * 立替記録（team_payment_advances）の ID も返し、チーム精算フローへつなぐ。casing は camelCase。</p>
 */
@Builder
public record PaymentRequestPayResponse(
        UUID paymentRequestId,
        UUID escrowTransactionId,
        UUID advanceId,
        String clientSecret) {

    /**
     * サービス結果を DTO へ写像する。
     */
    public static PaymentRequestPayResponse from(PaymentRequestPayResult r) {
        return PaymentRequestPayResponse.builder()
                .paymentRequestId(r.paymentRequestId())
                .escrowTransactionId(r.escrowTransactionId())
                .advanceId(r.advanceId())
                .clientSecret(r.clientSecret())
                .build();
    }
}
