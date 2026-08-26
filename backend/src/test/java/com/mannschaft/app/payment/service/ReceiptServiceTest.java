package com.mannschaft.app.payment.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.payment.PaymentErrorCode;
import com.mannschaft.app.payment.PaymentStatus;
import com.mannschaft.app.payment.dto.ReceiptResponse;
import com.mannschaft.app.payment.entity.MemberPaymentEntity;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * ReceiptService ユニットテスト（F08.9 P8 T-RS-01〜04）。
 *
 * <h3>テスト観点</h3>
 * <ul>
 *   <li>T-RS-01: 払い手本人がアクセス → 正常返却</li>
 *   <li>T-RS-02: 受益者本人がアクセス → 正常返却</li>
 *   <li>T-RS-03: 第三者がアクセス → PAYMENT_ACCESS_DENIED 例外</li>
 *   <li>T-RS-04: 存在しない ID → MEMBER_PAYMENT_NOT_FOUND 例外</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReceiptService ユニットテスト（T-RS-01〜04）")
class ReceiptServiceTest {

    private static final Long PAYMENT_ID = 1L;
    private static final Long PAYER_USER_ID = 10L;
    private static final Long BENEFICIARY_USER_ID = 20L;
    private static final Long OTHER_USER_ID = 99L;

    @Mock
    private MemberPaymentRepository memberPaymentRepository;

    @InjectMocks
    private ReceiptService receiptService;

    /**
     * テスト用 MemberPaymentEntity を生成する。
     */
    private MemberPaymentEntity buildPayment() {
        return MemberPaymentEntity.builder()
                .userId(BENEFICIARY_USER_ID)
                .payerUserId(PAYER_USER_ID)
                .paymentItemId(100L)
                .amountPaid(new BigDecimal("5000.00"))
                .currency("JPY")
                .stripeReceiptUrl("https://pay.stripe.com/receipts/test_receipt")
                .build();
    }

    // -------------------------------------------------------------------------
    // T-RS-01: 払い手本人がアクセス → 正常返却
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("T-RS-01: 払い手本人がアクセス → 正常な ReceiptResponse が返る")
    void getReceipt_payerAccess_returnsReceipt() {
        MemberPaymentEntity payment = buildPayment();
        given(memberPaymentRepository.findById(PAYMENT_ID)).willReturn(Optional.of(payment));

        ReceiptResponse result = receiptService.getReceipt(PAYMENT_ID, PAYER_USER_ID);

        assertThat(result).isNotNull();
        assertThat(result.memberPaymentId()).isNull(); // id は BaseEntity 管理のため null
        assertThat(result.amount()).isEqualByComparingTo(new BigDecimal("5000.00"));
        assertThat(result.currency()).isEqualTo("JPY");
        assertThat(result.receiptUrl()).isEqualTo("https://pay.stripe.com/receipts/test_receipt");
        assertThat(result.taxInfo()).isNull();
    }

    // -------------------------------------------------------------------------
    // T-RS-02: 受益者本人がアクセス → 正常返却
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("T-RS-02: 受益者本人がアクセス → 正常な ReceiptResponse が返る")
    void getReceipt_beneficiaryAccess_returnsReceipt() {
        MemberPaymentEntity payment = buildPayment();
        given(memberPaymentRepository.findById(PAYMENT_ID)).willReturn(Optional.of(payment));

        ReceiptResponse result = receiptService.getReceipt(PAYMENT_ID, BENEFICIARY_USER_ID);

        assertThat(result).isNotNull();
        assertThat(result.currency()).isEqualTo("JPY");
    }

    // -------------------------------------------------------------------------
    // T-RS-03: 第三者がアクセス → PAYMENT_ACCESS_DENIED 例外
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("T-RS-03: 第三者がアクセス → BusinessException(PAYMENT_ACCESS_DENIED) がスローされる")
    void getReceipt_otherUser_throwsAccessDenied() {
        MemberPaymentEntity payment = buildPayment();
        given(memberPaymentRepository.findById(PAYMENT_ID)).willReturn(Optional.of(payment));

        assertThatThrownBy(() -> receiptService.getReceipt(PAYMENT_ID, OTHER_USER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_ACCESS_DENIED);
                });
    }

    // -------------------------------------------------------------------------
    // T-RS-04: 存在しない ID → MEMBER_PAYMENT_NOT_FOUND 例外
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("T-RS-04: 存在しない ID → BusinessException(MEMBER_PAYMENT_NOT_FOUND) がスローされる")
    void getReceipt_notFound_throwsMemberPaymentNotFound() {
        given(memberPaymentRepository.findById(PAYMENT_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> receiptService.getReceipt(PAYMENT_ID, PAYER_USER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getErrorCode()).isEqualTo(PaymentErrorCode.MEMBER_PAYMENT_NOT_FOUND);
                });
    }
}
