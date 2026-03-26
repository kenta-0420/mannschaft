package com.mannschaft.app.payment;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.payment.dto.CreateManualPaymentRequest;
import com.mannschaft.app.payment.dto.MemberPaymentResponse;
import com.mannschaft.app.payment.entity.MemberPaymentEntity;
import com.mannschaft.app.payment.entity.PaymentItemEntity;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import com.mannschaft.app.payment.repository.StripeCustomerRepository;
import com.mannschaft.app.payment.service.MemberPaymentService;
import com.mannschaft.app.payment.service.PaymentItemService;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link MemberPaymentService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MemberPaymentService 単体テスト")
class MemberPaymentServiceTest {

    @Mock private MemberPaymentRepository memberPaymentRepository;
    @Mock private StripeCustomerRepository stripeCustomerRepository;
    @Mock private PaymentItemService paymentItemService;
    @Mock private StripePaymentProvider stripePaymentProvider;
    @Mock private PaymentMapper paymentMapper;
    @Mock private NameResolverService nameResolverService;

    @InjectMocks
    private MemberPaymentService service;

    private static final Long PAYMENT_ITEM_ID = 1L;
    private static final Long PAYMENT_ID = 10L;
    private static final Long USER_ID = 100L;

    @Nested
    @DisplayName("createManualPayment")
    class CreateManualPayment {

        @Test
        @DisplayName("異常系: DONATION以外で既に支払い済みの場合エラー")
        void 重複支払いエラー() {
            PaymentItemEntity item = PaymentItemEntity.builder()
                    .type(PaymentItemType.ANNUAL_FEE).currency("JPY").build();
            given(paymentItemService.findByIdOrThrow(PAYMENT_ITEM_ID)).willReturn(item);
            given(memberPaymentRepository.existsValidPaidPayment(USER_ID, PAYMENT_ITEM_ID)).willReturn(true);

            CreateManualPaymentRequest request = new CreateManualPaymentRequest(
                    USER_ID, new BigDecimal("5000"), LocalDateTime.now(),
                    null, null, null);

            assertThatThrownBy(() -> service.createManualPayment(PAYMENT_ITEM_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(PaymentErrorCode.ALREADY_PAID);
        }

        @Test
        @DisplayName("正常系: 手動支払い記録が正常に作成される")
        void 正常作成() {
            PaymentItemEntity item = PaymentItemEntity.builder()
                    .type(PaymentItemType.ANNUAL_FEE).currency("JPY").build();
            given(paymentItemService.findByIdOrThrow(PAYMENT_ITEM_ID)).willReturn(item);
            given(memberPaymentRepository.existsValidPaidPayment(USER_ID, PAYMENT_ITEM_ID)).willReturn(false);

            MemberPaymentEntity saved = MemberPaymentEntity.builder()
                    .userId(USER_ID).paymentItemId(PAYMENT_ITEM_ID).build();
            given(memberPaymentRepository.save(any())).willReturn(saved);
            given(paymentMapper.toMemberPaymentResponse(any())).willReturn(null);

            CreateManualPaymentRequest request = new CreateManualPaymentRequest(
                    USER_ID, new BigDecimal("5000"), LocalDateTime.now(),
                    null, null, null);

            service.createManualPayment(PAYMENT_ITEM_ID, USER_ID, request);

            verify(memberPaymentRepository).save(any());
        }
    }

    @Nested
    @DisplayName("cancelPayment")
    class CancelPayment {

        @Test
        @DisplayName("異常系: 既に返金/キャンセル済みはエラー")
        void 既に返金済み() {
            MemberPaymentEntity entity = MemberPaymentEntity.builder()
                    .userId(USER_ID).paymentItemId(PAYMENT_ITEM_ID)
                    .status(PaymentStatus.REFUNDED).build();
            given(memberPaymentRepository.findByIdAndPaymentItemId(PAYMENT_ID, PAYMENT_ITEM_ID))
                    .willReturn(Optional.of(entity));

            assertThatThrownBy(() -> service.cancelPayment(PAYMENT_ITEM_ID, PAYMENT_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(PaymentErrorCode.ALREADY_REFUNDED);
        }
    }

    @Nested
    @DisplayName("refundPayment")
    class RefundPayment {

        @Test
        @DisplayName("異常系: 手動支払いの返金はエラー")
        void 手動支払い返金不可() {
            MemberPaymentEntity entity = MemberPaymentEntity.builder()
                    .userId(USER_ID).paymentItemId(PAYMENT_ITEM_ID)
                    .paymentMethod(PaymentMethod.MANUAL).status(PaymentStatus.PAID).build();
            given(memberPaymentRepository.findByIdAndPaymentItemId(PAYMENT_ID, PAYMENT_ITEM_ID))
                    .willReturn(Optional.of(entity));

            assertThatThrownBy(() -> service.refundPayment(PAYMENT_ITEM_ID, PAYMENT_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(PaymentErrorCode.MANUAL_PAYMENT_NOT_REFUNDABLE);
        }

        @Test
        @DisplayName("異常系: PENDING状態の返金はエラー")
        void PENDING返金不可() {
            MemberPaymentEntity entity = MemberPaymentEntity.builder()
                    .userId(USER_ID).paymentItemId(PAYMENT_ITEM_ID)
                    .paymentMethod(PaymentMethod.STRIPE).status(PaymentStatus.PENDING).build();
            given(memberPaymentRepository.findByIdAndPaymentItemId(PAYMENT_ID, PAYMENT_ITEM_ID))
                    .willReturn(Optional.of(entity));

            assertThatThrownBy(() -> service.refundPayment(PAYMENT_ITEM_ID, PAYMENT_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(PaymentErrorCode.PENDING_PAYMENT_NOT_REFUNDABLE);
        }
    }

    @Nested
    @DisplayName("sendRemind")
    class SendRemind {

        @Test
        @DisplayName("異常系: DONATION にはリマインド不可")
        void DONATION_リマインド不可() {
            PaymentItemEntity item = PaymentItemEntity.builder()
                    .type(PaymentItemType.DONATION).build();
            given(paymentItemService.findByIdOrThrow(PAYMENT_ITEM_ID)).willReturn(item);

            assertThatThrownBy(() -> service.sendRemind(PAYMENT_ITEM_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(PaymentErrorCode.DONATION_REMIND_NOT_ALLOWED);
        }
    }

    @Nested
    @DisplayName("createCheckout")
    class CreateCheckout {

        @Test
        @DisplayName("異常系: Stripe Price 未設定でエラー")
        void Stripe_Price未設定() {
            PaymentItemEntity item = PaymentItemEntity.builder()
                    .type(PaymentItemType.ANNUAL_FEE).stripePriceId(null).build();
            given(paymentItemService.findByIdOrThrow(PAYMENT_ITEM_ID)).willReturn(item);

            assertThatThrownBy(() -> service.createCheckout(PAYMENT_ITEM_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(PaymentErrorCode.STRIPE_PRICE_NOT_SET);
        }
    }
}
