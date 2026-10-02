package com.mannschaft.app.payment.dto;

import com.mannschaft.app.payment.PaymentMethod;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CreateManualPaymentRequest} / {@link BulkPaymentRequest} のバリデーション制約テスト
 * （POST payments / payments/bulk の 400 契約の根拠）。
 *
 * <p>Controller は {@code @Valid @RequestBody}（TeamPaymentController:78/106）を付与しており、
 * 制約違反は Spring MVC が {@code MethodArgumentNotValidException} に変換し 400 を返す。
 * 本テストはその制約自体が発火することを純 UT（jakarta Validator・Docker不要）で担保する。</p>
 *
 * <p>会費「手動入金管理の実用化」: paymentMethod 任意・既定 MANUAL・STRIPE 指定は手動記録で禁止（詐称防止）。</p>
 */
@DisplayName("CreateManualPaymentRequest / BulkPaymentRequest バリデーション制約")
class CreateManualPaymentRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) {
            factory.close();
        }
    }

    private CreateManualPaymentRequest validRequest(PaymentMethod method) {
        return new CreateManualPaymentRequest(
                100L, new BigDecimal("5000"), LocalDateTime.now(),
                null, null, "メモ", method);
    }

    @Test
    @DisplayName("正常系: paymentMethod=CASH は制約違反なし")
    void cashMethod_noViolations() {
        Set<ConstraintViolation<CreateManualPaymentRequest>> violations =
                validator.validate(validRequest(PaymentMethod.CASH));
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("正常系: paymentMethod=BANK_TRANSFER は制約違反なし")
    void bankTransferMethod_noViolations() {
        Set<ConstraintViolation<CreateManualPaymentRequest>> violations =
                validator.validate(validRequest(PaymentMethod.BANK_TRANSFER));
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("正常系: paymentMethod=null(未指定) は制約違反なし（既定 MANUAL フォールバック）[AC-2]")
    void nullMethod_noViolations() {
        Set<ConstraintViolation<CreateManualPaymentRequest>> violations =
                validator.validate(validRequest(null));
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("正常系: paymentMethod=MANUAL は制約違反なし")
    void manualMethod_noViolations() {
        Set<ConstraintViolation<CreateManualPaymentRequest>> violations =
                validator.validate(validRequest(PaymentMethod.MANUAL));
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("[AC-7] paymentMethod=STRIPE を手動記録で指定→制約違反（Stripe詐称防止400）")
    void stripeMethod_violatesConstraint() {
        Set<ConstraintViolation<CreateManualPaymentRequest>> violations =
                validator.validate(validRequest(PaymentMethod.STRIPE));
        assertThat(violations).isNotEmpty();
        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .anyMatch(p -> p.contains("paymentMethod") || p.contains("paymentMethodAllowedForManual"));
    }

    @Test
    @DisplayName("[AC-8] amountPaid=0 は @DecimalMin 違反（400）")
    void zeroAmount_violatesDecimalMin() {
        CreateManualPaymentRequest req = new CreateManualPaymentRequest(
                100L, BigDecimal.ZERO, LocalDateTime.now(), null, null, null, PaymentMethod.CASH);
        Set<ConstraintViolation<CreateManualPaymentRequest>> violations = validator.validate(req);
        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("amountPaid");
    }

    @Test
    @DisplayName("[AC-8] amountPaid が負数は @DecimalMin 違反（400）")
    void negativeAmount_violatesDecimalMin() {
        CreateManualPaymentRequest req = new CreateManualPaymentRequest(
                100L, new BigDecimal("-1"), LocalDateTime.now(), null, null, null, PaymentMethod.CASH);
        Set<ConstraintViolation<CreateManualPaymentRequest>> violations = validator.validate(req);
        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("amountPaid");
    }

    @Test
    @DisplayName("[AC-8] userId=null は @NotNull 違反（400）")
    void nullUserId_violatesNotNull() {
        CreateManualPaymentRequest req = new CreateManualPaymentRequest(
                null, new BigDecimal("5000"), LocalDateTime.now(), null, null, null, PaymentMethod.CASH);
        Set<ConstraintViolation<CreateManualPaymentRequest>> violations = validator.validate(req);
        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("userId");
    }

    @Test
    @DisplayName("[AC-8] paidAt=null は @NotNull 違反（400）")
    void nullPaidAt_violatesNotNull() {
        CreateManualPaymentRequest req = new CreateManualPaymentRequest(
                100L, new BigDecimal("5000"), null, null, null, null, PaymentMethod.CASH);
        Set<ConstraintViolation<CreateManualPaymentRequest>> violations = validator.validate(req);
        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("paidAt");
    }

    @Test
    @DisplayName("[AC-9] note 501文字は @Size(max=500) 違反（400）")
    void note501Chars_violatesSize() {
        String note = "あ".repeat(501);
        CreateManualPaymentRequest req = new CreateManualPaymentRequest(
                100L, new BigDecimal("5000"), LocalDateTime.now(), null, null, note, PaymentMethod.CASH);
        Set<ConstraintViolation<CreateManualPaymentRequest>> violations = validator.validate(req);
        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("note");
    }

    @Test
    @DisplayName("[AC-9] bulk 51件は @Size(max=50) 違反（400）")
    void bulk51Entries_violatesSize() {
        List<CreateManualPaymentRequest> payments = java.util.stream.IntStream.range(0, 51)
                .mapToObj(i -> validRequest(PaymentMethod.CASH))
                .toList();
        BulkPaymentRequest req = new BulkPaymentRequest(payments);
        Set<ConstraintViolation<BulkPaymentRequest>> violations = validator.validate(req);
        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("payments");
    }

    @Test
    @DisplayName("[AC-9] bulk 50件は制約違反なし（上限内）")
    void bulk50Entries_noViolations() {
        List<CreateManualPaymentRequest> payments = java.util.stream.IntStream.range(0, 50)
                .mapToObj(i -> validRequest(PaymentMethod.CASH))
                .toList();
        BulkPaymentRequest req = new BulkPaymentRequest(payments);
        Set<ConstraintViolation<BulkPaymentRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }
}
