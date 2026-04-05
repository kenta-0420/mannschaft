package com.mannschaft.app.payment;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.payment.entity.MemberPaymentEntity;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import com.mannschaft.app.payment.service.PaymentItemService;
import com.mannschaft.app.payment.service.StripeWebhookService;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link StripeWebhookService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StripeWebhookService 単体テスト")
class StripeWebhookServiceTest {

    @Mock private MemberPaymentRepository memberPaymentRepository;
    @Mock private PaymentItemService paymentItemService;
    @Mock private StripePaymentProvider stripePaymentProvider;

    @InjectMocks
    private StripeWebhookService service;

    @Nested
    @DisplayName("handleWebhook")
    class HandleWebhook {

        @Test
        @DisplayName("異常系: 署名検証失敗時はエラー")
        void 署名検証失敗() {
            given(stripePaymentProvider.constructEvent(any(), any()))
                    .willThrow(new RuntimeException("Invalid signature"));

            assertThatThrownBy(() -> service.handleWebhook("payload", "sig"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(PaymentErrorCode.WEBHOOK_SIGNATURE_INVALID);
        }

        @Test
        @DisplayName("正常系: checkout.session.completed で冪等処理（既にPAID済みスキップ）")
        void 冪等処理_PAID済みスキップ() {
            StripePaymentProvider.WebhookEventInfo event = new StripePaymentProvider.WebhookEventInfo(
                    "checkout.session.completed", null, "pi_xxx", "1", null,
                    new BigDecimal("5000"), "https://receipt.url", null, null, null);
            given(stripePaymentProvider.constructEvent(any(), any())).willReturn(event);

            MemberPaymentEntity payment = MemberPaymentEntity.builder()
                    .userId(1L).paymentItemId(1L).status(PaymentStatus.PAID).build();
            given(memberPaymentRepository.findById(1L)).willReturn(Optional.of(payment));

            service.handleWebhook("payload", "sig");

            verify(memberPaymentRepository, never()).save(any());
        }
    }
}
