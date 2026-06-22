package com.mannschaft.app.payment;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.payment.connect.ConnectAccountEntity;
import com.mannschaft.app.payment.connect.ConnectAccountRepository;
import com.mannschaft.app.payment.connect.ConnectPaymentErrorCode;
import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.dto.ConnectCheckoutResponse;
import com.mannschaft.app.payment.entity.MemberPaymentEntity;
import com.mannschaft.app.payment.entity.PaymentItemEntity;
import com.mannschaft.app.payment.entity.StripeCustomerEntity;
import com.mannschaft.app.payment.escrow.ConnectChargeService;
import com.mannschaft.app.payment.escrow.EscrowStatus;
import com.mannschaft.app.payment.escrow.MembershipChargeCommand;
import com.mannschaft.app.payment.escrow.MembershipChargeResult;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import com.mannschaft.app.payment.repository.StripeCustomerRepository;
import com.mannschaft.app.payment.service.MemberPaymentService;
import com.mannschaft.app.payment.service.PaymentAuthorizationService;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F08.9 P1 Wave4 (T7): {@link MemberPaymentService#createConnectCheckout} の単体テスト。
 *
 * <p>払い手分離＋Connect 即時 charge の起票フロー（本人払い成立・無権原 403・重複 409・口座非 READY 409）を
 * Mockito で検証する。escrow charge / Repository / 認可サービスをすべてモックする。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MemberPaymentService Connect Checkout 単体テスト (T7)")
class MemberPaymentConnectCheckoutServiceTest {

    @Mock private MemberPaymentRepository memberPaymentRepository;
    @Mock private StripeCustomerRepository stripeCustomerRepository;
    @Mock private PaymentItemService paymentItemService;
    @Mock private StripePaymentProvider stripePaymentProvider;
    @Mock private PaymentMapper paymentMapper;
    @Mock private NameResolverService nameResolverService;
    @Mock private NotificationHelper notificationHelper;
    @Mock private PaymentAuthorizationService paymentAuthorizationService;
    @Mock private ConnectChargeService connectChargeService;
    @Mock private ConnectAccountRepository connectAccountRepository;

    @InjectMocks
    private MemberPaymentService service;

    private static final Long ITEM_ID = 1L;
    private static final Long TEAM_ID = 50L;
    private static final Long BENEFICIARY = 100L;
    private static final Long PAYER = 100L;            // P1: SELF（払い手=受益者）
    private static final Long OTHER_PAYER = 999L;      // 無権原の他人
    private static final String IDEMPOTENCY_KEY = "idem-key-xyz";
    private static final UUID ESCROW_ID = UUID.randomUUID();
    private static final UUID ACCOUNT_ID = UUID.randomUUID();

    private PaymentItemEntity teamItem() {
        return PaymentItemEntity.builder()
                .teamId(TEAM_ID)
                .type(PaymentItemType.ANNUAL_FEE)
                .amount(new BigDecimal("5000"))
                .currency("JPY")
                .build();
    }

    private ConnectAccountEntity readyAccount() {
        ConnectAccountEntity acc = ConnectAccountEntity.builder()
                .scopeKind(ScopeKind.TEAM)
                .scopeId(TEAM_ID)
                .stripeAccountId("acct_ready")
                .payoutsEnabled(true)
                .chargesEnabled(true)
                .build();
        acc.setId(ACCOUNT_ID);
        return acc;
    }

    @Nested
    @DisplayName("createConnectCheckout")
    class CreateConnectCheckout {

        @Test
        @DisplayName("正常系: 本人払い(SELF)で escrow 連結・member_payments を PENDING 起票")
        void 本人払いでPENDING起票() {
            given(paymentItemService.findByIdOrThrow(ITEM_ID)).willReturn(teamItem());
            given(paymentAuthorizationService.authorizePayment(PAYER, BENEFICIARY, ITEM_ID, false))
                    .willReturn(PayerRelationship.SELF);
            given(memberPaymentRepository.existsValidPaidPayment(BENEFICIARY, ITEM_ID)).willReturn(false);
            given(connectAccountRepository.findByScopeKindAndScopeIdAndDeletedAtIsNull(ScopeKind.TEAM, TEAM_ID))
                    .willReturn(Optional.of(readyAccount()));
            given(stripeCustomerRepository.findByUserId(PAYER))
                    .willReturn(Optional.of(StripeCustomerEntity.builder()
                            .userId(PAYER).stripeCustomerId("cus_payer").build()));
            given(connectChargeService.charge(any(MembershipChargeCommand.class)))
                    .willReturn(new MembershipChargeResult(ESCROW_ID, "cs_secret", "pi_123", EscrowStatus.AUTHORIZED));
            given(memberPaymentRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            ConnectCheckoutResponse response = service.createConnectCheckout(
                    ITEM_ID, BENEFICIARY, PAYER, IDEMPOTENCY_KEY);

            // charge コマンドの中身を検証（faceAmount=額面・払い手 Customer・払い手 ID・冪等キー）。
            ArgumentCaptor<MembershipChargeCommand> cmdCaptor = ArgumentCaptor.forClass(MembershipChargeCommand.class);
            verify(connectChargeService).charge(cmdCaptor.capture());
            MembershipChargeCommand cmd = cmdCaptor.getValue();
            assertThat(cmd.faceAmount()).isEqualTo(5000L);
            assertThat(cmd.payeeConnectAccountId()).isEqualTo(ACCOUNT_ID);
            assertThat(cmd.payerStripeCustomerId()).isEqualTo("cus_payer");
            assertThat(cmd.payerUserId()).isEqualTo(PAYER);
            assertThat(cmd.sourceId()).isEqualTo(ITEM_ID);
            assertThat(cmd.idempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);

            // 起票された member_payment は PENDING・払い手列・escrow_transaction_id を持つ。
            ArgumentCaptor<MemberPaymentEntity> payCaptor = ArgumentCaptor.forClass(MemberPaymentEntity.class);
            verify(memberPaymentRepository).save(payCaptor.capture());
            MemberPaymentEntity saved = payCaptor.getValue();
            assertThat(saved.getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(saved.getUserId()).isEqualTo(BENEFICIARY);
            assertThat(saved.getPayerUserId()).isEqualTo(PAYER);
            assertThat(saved.getPayerRelationship()).isEqualTo(PayerRelationship.SELF);
            assertThat(saved.getEscrowTransactionId()).isEqualTo(ESCROW_ID);
            assertThat(saved.getPaymentMethod()).isEqualTo(PaymentMethod.STRIPE);

            assertThat(response.getClientSecret()).isEqualTo("cs_secret");
            assertThat(response.getEscrowTransactionId()).isEqualTo(ESCROW_ID);
        }

        @Test
        @DisplayName("異常系: 無権原(他人)は 403 で起票しない")
        void 無権原は403() {
            given(paymentItemService.findByIdOrThrow(ITEM_ID)).willReturn(teamItem());
            given(paymentAuthorizationService.authorizePayment(OTHER_PAYER, BENEFICIARY, ITEM_ID, false))
                    .willThrow(new BusinessException(MembershipBillingErrorCode.MEMBERSHIP_PAYER_NOT_AUTHORIZED));

            assertThatThrownBy(() -> service.createConnectCheckout(ITEM_ID, BENEFICIARY, OTHER_PAYER, IDEMPOTENCY_KEY))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MembershipBillingErrorCode.MEMBERSHIP_PAYER_NOT_AUTHORIZED);

            verify(connectChargeService, never()).charge(any());
            verify(memberPaymentRepository, never()).save(any());
        }

        @Test
        @DisplayName("異常系: 既に支払い済み(受益者×項目)は 409")
        void 重複は409() {
            given(paymentItemService.findByIdOrThrow(ITEM_ID)).willReturn(teamItem());
            given(paymentAuthorizationService.authorizePayment(PAYER, BENEFICIARY, ITEM_ID, false))
                    .willReturn(PayerRelationship.SELF);
            given(memberPaymentRepository.existsValidPaidPayment(BENEFICIARY, ITEM_ID)).willReturn(true);

            assertThatThrownBy(() -> service.createConnectCheckout(ITEM_ID, BENEFICIARY, PAYER, IDEMPOTENCY_KEY))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MembershipBillingErrorCode.MEMBERSHIP_ALREADY_PAID);

            verify(connectChargeService, never()).charge(any());
        }

        @Test
        @DisplayName("異常系: 受領口座が非 READY(payouts_enabled=false)は 409・即時モードゆえ HELD にしない")
        void 口座非READYは409() {
            ConnectAccountEntity notReady = ConnectAccountEntity.builder()
                    .scopeKind(ScopeKind.TEAM).scopeId(TEAM_ID)
                    .stripeAccountId("acct_not_ready")
                    .payoutsEnabled(false).chargesEnabled(false)
                    .build();
            notReady.setId(ACCOUNT_ID);

            given(paymentItemService.findByIdOrThrow(ITEM_ID)).willReturn(teamItem());
            given(paymentAuthorizationService.authorizePayment(PAYER, BENEFICIARY, ITEM_ID, false))
                    .willReturn(PayerRelationship.SELF);
            given(memberPaymentRepository.existsValidPaidPayment(BENEFICIARY, ITEM_ID)).willReturn(false);
            given(connectAccountRepository.findByScopeKindAndScopeIdAndDeletedAtIsNull(ScopeKind.TEAM, TEAM_ID))
                    .willReturn(Optional.of(notReady));

            assertThatThrownBy(() -> service.createConnectCheckout(ITEM_ID, BENEFICIARY, PAYER, IDEMPOTENCY_KEY))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ConnectPaymentErrorCode.ONBOARDING_NOT_READY);

            verify(connectChargeService, never()).charge(any());
        }

        @Test
        @DisplayName("異常系: 受領口座が未登録は 409(ONBOARDING_NOT_READY)")
        void 口座未登録は409() {
            given(paymentItemService.findByIdOrThrow(ITEM_ID)).willReturn(teamItem());
            given(paymentAuthorizationService.authorizePayment(PAYER, BENEFICIARY, ITEM_ID, false))
                    .willReturn(PayerRelationship.SELF);
            given(memberPaymentRepository.existsValidPaidPayment(BENEFICIARY, ITEM_ID)).willReturn(false);
            given(connectAccountRepository.findByScopeKindAndScopeIdAndDeletedAtIsNull(ScopeKind.TEAM, TEAM_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.createConnectCheckout(ITEM_ID, BENEFICIARY, PAYER, IDEMPOTENCY_KEY))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ConnectPaymentErrorCode.ONBOARDING_NOT_READY);
        }
    }

    @Nested
    @DisplayName("applyMembershipPaidByEscrow (T8)")
    class ApplyMembershipPaidByEscrow {

        @Test
        @DisplayName("正常系: PENDING を PAID 化し valid_until を設定")
        void PENDINGをPAID化() {
            MemberPaymentEntity pending = MemberPaymentEntity.builder()
                    .userId(BENEFICIARY).paymentItemId(ITEM_ID)
                    .amountPaid(new BigDecimal("5000")).currency("JPY")
                    .paymentMethod(PaymentMethod.STRIPE).status(PaymentStatus.PENDING)
                    .escrowTransactionId(ESCROW_ID)
                    .build();
            given(memberPaymentRepository.findByEscrowTransactionId(ESCROW_ID)).willReturn(Optional.of(pending));
            given(paymentItemService.findByIdOrThrow(ITEM_ID)).willReturn(teamItem());

            service.applyMembershipPaidByEscrow(ESCROW_ID);

            assertThat(pending.getStatus()).isEqualTo(PaymentStatus.PAID);
            assertThat(pending.getValidFrom()).isEqualTo(LocalDate.now());
            assertThat(pending.getValidUntil()).isEqualTo(LocalDate.now().plusDays(365)); // ANNUAL_FEE
            assertThat(pending.getPaidAt()).isNotNull();
            verify(memberPaymentRepository).save(pending);
        }

        @Test
        @DisplayName("冪等: 既に PAID なら no-op(save しない)")
        void 既にPAIDはNoOp() {
            MemberPaymentEntity paid = MemberPaymentEntity.builder()
                    .userId(BENEFICIARY).paymentItemId(ITEM_ID)
                    .amountPaid(new BigDecimal("5000")).currency("JPY")
                    .paymentMethod(PaymentMethod.STRIPE).status(PaymentStatus.PAID)
                    .escrowTransactionId(ESCROW_ID)
                    .build();
            given(memberPaymentRepository.findByEscrowTransactionId(ESCROW_ID)).willReturn(Optional.of(paid));

            service.applyMembershipPaidByEscrow(ESCROW_ID);

            verify(memberPaymentRepository, never()).save(any());
            verify(paymentItemService, never()).findByIdOrThrow(any());
        }

        @Test
        @DisplayName("冪等: 対応する member_payment が無ければ no-op")
        void 対象なしはNoOp() {
            given(memberPaymentRepository.findByEscrowTransactionId(ESCROW_ID)).willReturn(Optional.empty());

            service.applyMembershipPaidByEscrow(ESCROW_ID);

            verify(memberPaymentRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("createManualPayment 払い手列充填 (T7)")
    class CreateManualPaymentPayerColumns {

        @Test
        @DisplayName("正常系: ADMIN_MANUAL 認可を通り payer 列が埋まる")
        void 払い手列が埋まる() {
            given(paymentItemService.findByIdOrThrow(ITEM_ID)).willReturn(teamItem());
            given(memberPaymentRepository.existsValidPaidPayment(BENEFICIARY, ITEM_ID)).willReturn(false);
            given(paymentAuthorizationService.authorizePayment(eq(OTHER_PAYER), eq(BENEFICIARY), eq(ITEM_ID), anyBoolean()))
                    .willReturn(PayerRelationship.ADMIN_MANUAL);
            given(memberPaymentRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(paymentMapper.toMemberPaymentResponse(any())).willReturn(null);

            var request = new com.mannschaft.app.payment.dto.CreateManualPaymentRequest(
                    BENEFICIARY, new BigDecimal("5000"), java.time.LocalDateTime.now(), null, null, null, null);

            service.createManualPayment(ITEM_ID, OTHER_PAYER, request);

            ArgumentCaptor<MemberPaymentEntity> captor = ArgumentCaptor.forClass(MemberPaymentEntity.class);
            verify(memberPaymentRepository).save(captor.capture());
            MemberPaymentEntity saved = captor.getValue();
            assertThat(saved.getPayerUserId()).isEqualTo(OTHER_PAYER);
            assertThat(saved.getPayerRelationship()).isEqualTo(PayerRelationship.ADMIN_MANUAL);
            assertThat(saved.getRecordedBy()).isEqualTo(OTHER_PAYER);
        }
    }
}
