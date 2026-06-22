package com.mannschaft.app.payment.dto;

import com.mannschaft.app.payment.PaymentMethod;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 手動支払い記録リクエストDTO。
 *
 * <p>会費「手動入金管理の実用化」: ADMIN が会費の入金（現金・銀行振込など）を手動記録する。
 * {@link #paymentMethod} は任意で、未指定時は {@link PaymentMethod#MANUAL}（その他／不明）にフォールバックする。
 * {@link PaymentMethod#STRIPE} は手動記録では指定できない（オンライン決済を手動で詐称することを防ぐため・400）。</p>
 */
@Getter
@RequiredArgsConstructor
public class CreateManualPaymentRequest {

    @NotNull
    private final Long userId;

    @NotNull
    @DecimalMin(value = "0.01")
    private final BigDecimal amountPaid;

    @NotNull
    private final LocalDateTime paidAt;

    private final LocalDate validFrom;

    private final LocalDate validUntil;

    @Size(max = 500)
    private final String note;

    /**
     * 決済手段（任意）。未指定時はサービス層で {@link PaymentMethod#MANUAL} にフォールバックする。
     * {@link PaymentMethod#STRIPE} は手動記録では禁止（{@link #isPaymentMethodAllowedForManual()} で 400）。
     */
    @Schema(description = "決済手段（任意・未指定時は MANUAL）。STRIPE は手動記録では指定不可",
            allowableValues = {"CASH", "BANK_TRANSFER", "MANUAL"})
    private final PaymentMethod paymentMethod;

    /**
     * STRIPE は手動記録で指定できない（Stripe 詐称防止）。
     * null（未指定）または STRIPE 以外なら true。STRIPE のとき false → 400。
     */
    @AssertTrue(message = "paymentMethod に STRIPE は手動記録では指定できません")
    @Schema(hidden = true)
    public boolean isPaymentMethodAllowedForManual() {
        return paymentMethod != PaymentMethod.STRIPE;
    }
}
