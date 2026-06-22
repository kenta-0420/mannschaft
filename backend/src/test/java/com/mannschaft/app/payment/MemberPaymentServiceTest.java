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
                    null, null, null, null);

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
                    null, null, null, null);

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
                    null, null, null, null);

            MemberPaymentResponse response = service.createManualPayment(PAYMENT_ITEM_ID, USER_ID, request);

            assertThat(response.getUserName()).isEqualTo("山田 太郎");
            assertThat(response.getUserId()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("[AC-1] paymentMethod=CASH 指定で保存される payment_method が CASH")
        void CASH指定で保存される() {
            PaymentItemEntity item = PaymentItemEntity.builder()
                    .type(PaymentItemType.ANNUAL_FEE).currency("JPY").build();
            given(paymentItemService.findByIdOrThrow(PAYMENT_ITEM_ID)).willReturn(item);
            given(memberPaymentRepository.existsValidPaidPayment(USER_ID, PAYMENT_ITEM_ID)).willReturn(false);
            given(paymentAuthorizationService.authorizePayment(USER_ID, USER_ID, PAYMENT_ITEM_ID, true))
                    .willReturn(PayerRelationship.SELF);

            ArgumentCaptor<MemberPaymentEntity> captor = ArgumentCaptor.forClass(MemberPaymentEntity.class);
            given(memberPaymentRepository.save(captor.capture()))
                    .willReturn(MemberPaymentEntity.builder().userId(USER_ID).build());
            given(paymentMapper.toMemberPaymentResponse(any())).willReturn(null);

            CreateManualPaymentRequest request = new CreateManualPaymentRequest(
                    USER_ID, new BigDecimal("5000"), LocalDateTime.now(),
                    null, null, null, PaymentMethod.CASH);

            service.createManualPayment(PAYMENT_ITEM_ID, USER_ID, request);

            assertThat(captor.getValue().getPaymentMethod()).isEqualTo(PaymentMethod.CASH);
        }

        @Test
        @DisplayName("[AC-1] paymentMethod=BANK_TRANSFER 指定で保存される payment_method が BANK_TRANSFER")
        void BANK_TRANSFER指定で保存される() {
            PaymentItemEntity item = PaymentItemEntity.builder()
                    .type(PaymentItemType.ANNUAL_FEE).currency("JPY").build();
            given(paymentItemService.findByIdOrThrow(PAYMENT_ITEM_ID)).willReturn(item);
            given(memberPaymentRepository.existsValidPaidPayment(USER_ID, PAYMENT_ITEM_ID)).willReturn(false);
            given(paymentAuthorizationService.authorizePayment(USER_ID, USER_ID, PAYMENT_ITEM_ID, true))
                    .willReturn(PayerRelationship.SELF);

            ArgumentCaptor<MemberPaymentEntity> captor = ArgumentCaptor.forClass(MemberPaymentEntity.class);
            given(memberPaymentRepository.save(captor.capture()))
                    .willReturn(MemberPaymentEntity.builder().userId(USER_ID).build());
            given(paymentMapper.toMemberPaymentResponse(any())).willReturn(null);

            CreateManualPaymentRequest request = new CreateManualPaymentRequest(
                    USER_ID, new BigDecimal("5000"), LocalDateTime.now(),
                    null, null, null, PaymentMethod.BANK_TRANSFER);

            service.createManualPayment(PAYMENT_ITEM_ID, USER_ID, request);

            assertThat(captor.getValue().getPaymentMethod()).isEqualTo(PaymentMethod.BANK_TRANSFER);
        }

        @Test
        @DisplayName("[AC-2] paymentMethod 未指定(null)で保存される payment_method が MANUAL（後方互換）")
        void 未指定でMANUALフォールバック() {
            PaymentItemEntity item = PaymentItemEntity.builder()
                    .type(PaymentItemType.ANNUAL_FEE).currency("JPY").build();
            given(paymentItemService.findByIdOrThrow(PAYMENT_ITEM_ID)).willReturn(item);
            given(memberPaymentRepository.existsValidPaidPayment(USER_ID, PAYMENT_ITEM_ID)).willReturn(false);
            given(paymentAuthorizationService.authorizePayment(USER_ID, USER_ID, PAYMENT_ITEM_ID, true))
                    .willReturn(PayerRelationship.SELF);

            ArgumentCaptor<MemberPaymentEntity> captor = ArgumentCaptor.forClass(MemberPaymentEntity.class);
            given(memberPaymentRepository.save(captor.capture()))
                    .willReturn(MemberPaymentEntity.builder().userId(USER_ID).build());
            given(paymentMapper.toMemberPaymentResponse(any())).willReturn(null);

            CreateManualPaymentRequest request = new CreateManualPaymentRequest(
                    USER_ID, new BigDecimal("5000"), LocalDateTime.now(),
                    null, null, null, null);

            service.createManualPayment(PAYMENT_ITEM_ID, USER_ID, request);

            assertThat(captor.getValue().getPaymentMethod()).isEqualTo(PaymentMethod.MANUAL);
        }

        @Test
        @DisplayName("[AC-5] 非ADMIN(無権原)はMEMBERSHIP_PAYER_NOT_AUTHORIZED(403)— authorizePayment 経路不変")
        void 非ADMINは権原なしで拒否() {
            PaymentItemEntity item = PaymentItemEntity.builder()
                    .type(PaymentItemType.ANNUAL_FEE).currency("JPY").build();
            given(paymentItemService.findByIdOrThrow(PAYMENT_ITEM_ID)).willReturn(item);
            given(memberPaymentRepository.existsValidPaidPayment(any(), any())).willReturn(false);
            // 受益者 != 記録者 で権原なし → authorizePayment が 403 を投げる
            Long beneficiary = 999L;
            given(paymentAuthorizationService.authorizePayment(USER_ID, beneficiary, PAYMENT_ITEM_ID, true))
                    .willThrow(new BusinessException(
                            com.mannschaft.app.payment.MembershipBillingErrorCode.MEMBERSHIP_PAYER_NOT_AUTHORIZED));

            CreateManualPaymentRequest request = new CreateManualPaymentRequest(
                    beneficiary, new BigDecimal("5000"), LocalDateTime.now(),
                    null, null, null, PaymentMethod.CASH);

            assertThatThrownBy(() -> service.createManualPayment(PAYMENT_ITEM_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(com.mannschaft.app.payment.MembershipBillingErrorCode.MEMBERSHIP_PAYER_NOT_AUTHORIZED);
        }
    }

    @Nested
    @DisplayName("createBulkPayments")
    class CreateBulkPayments {

        @Test
        @DisplayName("[AC-3] bulk の各要素で手段が個別反映され createdCount が積まれる")
        void 各要素で手段が個別反映される() {
            PaymentItemEntity item = PaymentItemEntity.builder()
                    .type(PaymentItemType.ANNUAL_FEE).currency("JPY").build();
            given(paymentItemService.findByIdOrThrow(PAYMENT_ITEM_ID)).willReturn(item);
            given(memberPaymentRepository.existsValidPaidPayment(any(), any())).willReturn(false);

            ArgumentCaptor<MemberPaymentEntity> captor = ArgumentCaptor.forClass(MemberPaymentEntity.class);
            given(memberPaymentRepository.save(captor.capture()))
                    .willReturn(MemberPaymentEntity.builder().build());

            CreateManualPaymentRequest cash = new CreateManualPaymentRequest(
                    101L, new BigDecimal("5000"), LocalDateTime.now(), null, null, null, PaymentMethod.CASH);
            CreateManualPaymentRequest bank = new CreateManualPaymentRequest(
                    102L, new BigDecimal("3000"), LocalDateTime.now(), null, null, null, PaymentMethod.BANK_TRANSFER);
            CreateManualPaymentRequest deflt = new CreateManualPaymentRequest(
                    103L, new BigDecimal("2000"), LocalDateTime.now(), null, null, null, null);

            com.mannschaft.app.payment.dto.BulkPaymentResponse response =
                    service.createBulkPayments(PAYMENT_ITEM_ID, USER_ID,
                            new com.mannschaft.app.payment.dto.BulkPaymentRequest(
                                    java.util.List.of(cash, bank, deflt)));

            assertThat(response.getCreatedCount()).isEqualTo(3);
            assertThat(response.getSkippedCount()).isZero();
            assertThat(captor.getAllValues())
                    .extracting(MemberPaymentEntity::getPaymentMethod)
                    .containsExactly(PaymentMethod.CASH, PaymentMethod.BANK_TRANSFER, PaymentMethod.MANUAL);
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

        @Test
        @DisplayName("[AC-10] CASH の返金はMANUAL_PAYMENT_NOT_REFUNDABLE（非STRIPEは返金不可）")
        void CASH返金不可() {
            MemberPaymentEntity entity = MemberPaymentEntity.builder()
                    .userId(USER_ID).paymentItemId(PAYMENT_ITEM_ID)
                    .paymentMethod(PaymentMethod.CASH).status(PaymentStatus.PAID).build();
            given(memberPaymentRepository.findByIdAndPaymentItemId(PAYMENT_ID, PAYMENT_ITEM_ID))
                    .willReturn(Optional.of(entity));

            assertThatThrownBy(() -> service.refundPayment(PAYMENT_ITEM_ID, PAYMENT_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(PaymentErrorCode.MANUAL_PAYMENT_NOT_REFUNDABLE);
        }

        @Test
        @DisplayName("[AC-10] BANK_TRANSFER の返金はMANUAL_PAYMENT_NOT_REFUNDABLE（非STRIPEは返金不可）")
        void BANK_TRANSFER返金不可() {
            MemberPaymentEntity entity = MemberPaymentEntity.builder()
                    .userId(USER_ID).paymentItemId(PAYMENT_ITEM_ID)
                    .paymentMethod(PaymentMethod.BANK_TRANSFER).status(PaymentStatus.PAID).build();
            given(memberPaymentRepository.findByIdAndPaymentItemId(PAYMENT_ID, PAYMENT_ITEM_ID))
                    .willReturn(Optional.of(entity));

            assertThatThrownBy(() -> service.refundPayment(PAYMENT_ITEM_ID, PAYMENT_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(PaymentErrorCode.MANUAL_PAYMENT_NOT_REFUNDABLE);
        }

        @Test
        @DisplayName("[AC-14] 既存MANUALの返金はMANUAL_PAYMENT_NOT_REFUNDABLE（挙動不変）")
        void MANUAL返金不可_挙動不変() {
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
        @DisplayName("[AC-11] STRIPE/PAID の返金は正常に実行される")
        void STRIPE_PAID返金正常() {
            MemberPaymentEntity entity = MemberPaymentEntity.builder()
                    .userId(USER_ID).paymentItemId(PAYMENT_ITEM_ID)
                    .paymentMethod(PaymentMethod.STRIPE).status(PaymentStatus.PAID)
                    .stripePaymentIntentId("pi_123").build();
            given(memberPaymentRepository.findByIdAndPaymentItemId(PAYMENT_ID, PAYMENT_ITEM_ID))
                    .willReturn(Optional.of(entity));
            given(stripePaymentProvider.createRefund("pi_123", PAYMENT_ID, USER_ID))
                    .willReturn("re_456");
            given(memberPaymentRepository.save(any())).willReturn(entity);
            given(paymentMapper.toMemberPaymentResponse(any())).willReturn(null);

            service.refundPayment(PAYMENT_ITEM_ID, PAYMENT_ID, USER_ID);

            verify(stripePaymentProvider).createRefund("pi_123", PAYMENT_ID, USER_ID);
            assertThat(entity.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        }
    }

    @Nested
    @DisplayName("reconcile")
    class Reconcile {

        @Test
        @DisplayName("[AC-12] CASH の再同期はSTRIPE_PAYMENT_ONLY（非STRIPEは再同期不可）")
        void CASH再同期不可() {
            MemberPaymentEntity entity = MemberPaymentEntity.builder()
                    .userId(USER_ID).paymentItemId(PAYMENT_ITEM_ID)
                    .paymentMethod(PaymentMethod.CASH).status(PaymentStatus.PAID).build();
            given(memberPaymentRepository.findById(PAYMENT_ID)).willReturn(Optional.of(entity));

            assertThatThrownBy(() -> service.reconcile(PAYMENT_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(PaymentErrorCode.STRIPE_PAYMENT_ONLY);
        }

        @Test
        @DisplayName("[AC-12] BANK_TRANSFER の再同期はSTRIPE_PAYMENT_ONLY（非STRIPEは再同期不可）")
        void BANK_TRANSFER再同期不可() {
            MemberPaymentEntity entity = MemberPaymentEntity.builder()
                    .userId(USER_ID).paymentItemId(PAYMENT_ITEM_ID)
                    .paymentMethod(PaymentMethod.BANK_TRANSFER).status(PaymentStatus.PAID).build();
            given(memberPaymentRepository.findById(PAYMENT_ID)).willReturn(Optional.of(entity));

            assertThatThrownBy(() -> service.reconcile(PAYMENT_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(PaymentErrorCode.STRIPE_PAYMENT_ONLY);
        }

        @Test
        @DisplayName("[AC-12][AC-14] 既存MANULの再同期はSTRIPE_PAYMENT_ONLY（挙動不変）")
        void MANUAL再同期不可_挙動不変() {
            MemberPaymentEntity entity = MemberPaymentEntity.builder()
                    .userId(USER_ID).paymentItemId(PAYMENT_ITEM_ID)
                    .paymentMethod(PaymentMethod.MANUAL).status(PaymentStatus.PAID).build();
            given(memberPaymentRepository.findById(PAYMENT_ID)).willReturn(Optional.of(entity));

            assertThatThrownBy(() -> service.reconcile(PAYMENT_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(PaymentErrorCode.STRIPE_PAYMENT_ONLY);
        }

        @Test
        @DisplayName("[AC-13] STRIPE/PENDING の再同期は succeeded で PAID に同期される")
        void STRIPE再同期で同期される() {
            MemberPaymentEntity entity = MemberPaymentEntity.builder()
                    .userId(USER_ID).paymentItemId(PAYMENT_ITEM_ID)
                    .amountPaid(new BigDecimal("5000")).currency("JPY")
                    .paymentMethod(PaymentMethod.STRIPE).status(PaymentStatus.PENDING)
                    .stripeCheckoutSessionId("cs_123").build();
            given(memberPaymentRepository.findById(PAYMENT_ID)).willReturn(Optional.of(entity));

            PaymentItemEntity item = PaymentItemEntity.builder()
                    .type(PaymentItemType.ANNUAL_FEE).currency("JPY").build();
            given(paymentItemService.findByIdOrThrow(PAYMENT_ITEM_ID)).willReturn(item);
            given(stripePaymentProvider.retrieveSessionStatus("cs_123"))
                    .willReturn(new StripePaymentProvider.SessionStatusInfo(
                            "paid", "pi_789", "succeeded"));
            given(memberPaymentRepository.save(any())).willReturn(entity);

            com.mannschaft.app.payment.dto.ReconcileResponse response = service.reconcile(PAYMENT_ID);

            assertThat(response.isReconciled()).isTrue();
            assertThat(entity.getStatus()).isEqualTo(PaymentStatus.PAID);
        }
    }

    @Nested
    @DisplayName("exportPaymentsCsv")
    class ExportPaymentsCsv {

        @Test
        @DisplayName("[AC-15] 既存MANULレコードのCSVは payment_method=MANUAL のまま表示される")
        void MANUALレコードのCSVが値不変() {
            PaymentItemEntity item = PaymentItemEntity.builder()
                    .type(PaymentItemType.ANNUAL_FEE).currency("JPY").build();
            given(paymentItemService.findByIdOrThrow(PAYMENT_ITEM_ID)).willReturn(item);

            MemberPaymentEntity manual = MemberPaymentEntity.builder()
                    .userId(USER_ID).paymentItemId(PAYMENT_ITEM_ID)
                    .amountPaid(new BigDecimal("5000")).currency("JPY")
                    .paymentMethod(PaymentMethod.MANUAL).status(PaymentStatus.PAID).build();
            given(memberPaymentRepository.findByPaymentItemId(PAYMENT_ITEM_ID))
                    .willReturn(java.util.List.of(manual));
            given(nameResolverService.resolveUserDisplayNames(any()))
                    .willReturn(java.util.Map.of(USER_ID, "山田 太郎"));

            byte[] csv = service.exportPaymentsCsv(PAYMENT_ITEM_ID);
            String text = new String(csv, java.nio.charset.StandardCharsets.UTF_8);

            assertThat(text).contains("MANUAL");
            assertThat(text).contains("山田 太郎");
        }

        @Test
        @DisplayName("[AC-15] CASH レコードのCSVは payment_method=CASH で表示される")
        void CASHレコードのCSV表示() {
            PaymentItemEntity item = PaymentItemEntity.builder()
                    .type(PaymentItemType.ANNUAL_FEE).currency("JPY").build();
            given(paymentItemService.findByIdOrThrow(PAYMENT_ITEM_ID)).willReturn(item);

            MemberPaymentEntity cash = MemberPaymentEntity.builder()
                    .userId(USER_ID).paymentItemId(PAYMENT_ITEM_ID)
                    .amountPaid(new BigDecimal("5000")).currency("JPY")
                    .paymentMethod(PaymentMethod.CASH).status(PaymentStatus.PAID).build();
            given(memberPaymentRepository.findByPaymentItemId(PAYMENT_ITEM_ID))
                    .willReturn(java.util.List.of(cash));
            given(nameResolverService.resolveUserDisplayNames(any()))
                    .willReturn(java.util.Map.of(USER_ID, "山田 太郎"));

            byte[] csv = service.exportPaymentsCsv(PAYMENT_ITEM_ID);
            String text = new String(csv, java.nio.charset.StandardCharsets.UTF_8);

            assertThat(text).contains("CASH");
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
