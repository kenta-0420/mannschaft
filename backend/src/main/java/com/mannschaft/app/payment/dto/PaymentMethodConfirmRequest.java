package com.mannschaft.app.payment.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * F08.9 P5 第二波: 支払い方法 confirm リクエスト（設計書 02 §4.1）。
 *
 * <p>FE で SetupIntent を confirm して得た {@code payment_method_id}（{@code pm_xxx}）を Customer へ
 * attach＋既定設定するために送る。</p>
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class PaymentMethodConfirmRequest {

    /** confirm 済みの Stripe PaymentMethod ID（{@code pm_xxx}）。必須。 */
    @NotBlank
    private String paymentMethodId;
}
