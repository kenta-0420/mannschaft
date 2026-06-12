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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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
    @Mock private com.mannschaft.app.notification.service.NotificationHelper notificationHelper;
    @Mock private com.mannschaft.app.payment.service.PaymentAuthorizationService paymentAuthorizationService;
    @Mock private com.mannschaft.app.payment.escrow.ConnectChargeService connectChargeService;
    @Mock private com.mannschaft.app.payment.connect.ConnectAccountRepository connectAccountRepository;

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
            given(paymentAuthorizationService.authorizePayment(USER_ID, USER_ID, PAYMENT_ITEM_ID, true))
                    .willReturn(PayerRelationship.SELF);

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

        @Test
        @DisplayName("正常系: レスポンスに会員実名(userName)が充填される")
        void 会員実名が充填される() {
            PaymentItemEntity item = PaymentItemEntity.builder()
                    .type(PaymentItemType.ANNUAL_FEE).currency("JPY").build();
            given(paymentItemService.findByIdOrThrow(PAYMENT_ITEM_ID)).willReturn(item);
            given(memberPaymentRepository.existsValidPaidPayment(USER_ID, PAYMENT_ITEM_ID)).willReturn(false);
            given(paymentAuthorizationService.authorizePayment(USER_ID, USER_ID, PAYMENT_ITEM_ID, true))
                    .willReturn(PayerRelationship.SELF);

            MemberPaymentEntity saved = MemberPaymentEntity.builder()
                    .userId(USER_ID).paymentItemId(PAYMENT_ITEM_ID).build();
            given(memberPaymentRepository.save(any())).willReturn(saved);
            given(paymentMapper.toMemberPaymentResponse(any()))
                    .willReturn(MemberPaymentResponse.builder().userId(USER_ID).build());
            given(nameResolverService.resolveUserFullName(USER_ID)).willReturn("山田 太郎");

            CreateManualPaymentRequest request = new CreateManualPaymentRequest(
                    USER_ID, new BigDecimal("5000"), LocalDateTime.now(),
                    null, null, null);

            MemberPaymentResponse response = service.createManualPayment(PAYMENT_ITEM_ID, USER_ID, request);

            assertThat(response.getUserName()).isEqualTo("山田 太郎");
            assertThat(response.getUserId()).isEqualTo(USER_ID);
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

    @Nested
    @DisplayName("F08.9 P6: TERM 型 valid_until 設定")
    class TermTypeValidUntil {

        private static final LocalDate TERM_ENDS_ON = LocalDate.of(2026, 12, 31);
        private static final UUID ESCROW_ID = UUID.randomUUID();

        @Test
        @DisplayName("正常系: TERM 型の applyMembershipPaidByEscrow では valid_until = termEndsOn が設定される")
        void createConnectCheckout_termType_setsValidUntil() {
            // Arrange: TERM 型の payment item（termEndsOn 設定済み）
            PaymentItemEntity paymentItem = PaymentItemEntity.builder()
                    .type(PaymentItemType.TERM)
                    .termEndsOn(TERM_ENDS_ON)
                    .currency("JPY")
                    .amount(new BigDecimal("10000"))
                    .build();

            MemberPaymentEntity pendingPayment = MemberPaymentEntity.builder()
                    .userId(USER_ID)
                    .paymentItemId(PAYMENT_ITEM_ID)
                    .amountPaid(new BigDecimal("10000"))
                    .currency("JPY")
                    .paymentMethod(PaymentMethod.STRIPE)
                    .status(PaymentStatus.PENDING)
                    .escrowTransactionId(ESCROW_ID)
                    .build();

            given(memberPaymentRepository.findByEscrowTransactionId(ESCROW_ID))
                    .willReturn(Optional.of(pendingPayment));
            given(paymentItemService.findByIdOrThrow(PAYMENT_ITEM_ID)).willReturn(paymentItem);

            ArgumentCaptor<MemberPaymentEntity> captor = ArgumentCaptor.forClass(MemberPaymentEntity.class);
            given(memberPaymentRepository.save(captor.capture())).willReturn(pendingPayment);

            // Act
            service.applyMembershipPaidByEscrow(ESCROW_ID);

            // Assert: valid_until が termEndsOn と一致する
            MemberPaymentEntity saved = captor.getValue();
            assertThat(saved.getValidUntil()).isEqualTo(TERM_ENDS_ON);
            assertThat(saved.getStatus()).isEqualTo(PaymentStatus.PAID);
        }

        @Test
        @DisplayName("正常系: ITEM 型の applyMembershipPaidByEscrow では valid_until = null のまま")
        void createConnectCheckout_itemType_validUntilIsNull() {
            // Arrange: ITEM 型の payment item（termEndsOn なし）
            PaymentItemEntity paymentItem = PaymentItemEntity.builder()
                    .type(PaymentItemType.ITEM)
                    .currency("JPY")
                    .amount(new BigDecimal("1000"))
                    .build();

            MemberPaymentEntity pendingPayment = MemberPaymentEntity.builder()
                    .userId(USER_ID)
                    .paymentItemId(PAYMENT_ITEM_ID)
                    .amountPaid(new BigDecimal("1000"))
                    .currency("JPY")
                    .paymentMethod(PaymentMethod.STRIPE)
                    .status(PaymentStatus.PENDING)
                    .escrowTransactionId(ESCROW_ID)
                    .build();

            given(memberPaymentRepository.findByEscrowTransactionId(ESCROW_ID))
                    .willReturn(Optional.of(pendingPayment));
            given(paymentItemService.findByIdOrThrow(PAYMENT_ITEM_ID)).willReturn(paymentItem);

            ArgumentCaptor<MemberPaymentEntity> captor = ArgumentCaptor.forClass(MemberPaymentEntity.class);
            given(memberPaymentRepository.save(captor.capture())).willReturn(pendingPayment);

            // Act
            service.applyMembershipPaidByEscrow(ESCROW_ID);

            // Assert: valid_until は null のまま
            MemberPaymentEntity saved = captor.getValue();
            assertThat(saved.getValidUntil()).isNull();
            assertThat(saved.getStatus()).isEqualTo(PaymentStatus.PAID);
        }
    }
}
