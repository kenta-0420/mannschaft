package com.mannschaft.app.payment;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.payment.connect.ConnectAccountEntity;
import com.mannschaft.app.payment.connect.ConnectAccountRepository;
import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.entity.PaymentRequestEntity;
import com.mannschaft.app.payment.entity.StripeCustomerEntity;
import com.mannschaft.app.payment.entity.TeamPaymentAdvanceEntity;
import com.mannschaft.app.payment.escrow.ConnectChargeService;
import com.mannschaft.app.payment.escrow.EscrowStatus;
import com.mannschaft.app.payment.escrow.MembershipChargeCommand;
import com.mannschaft.app.payment.escrow.MembershipChargeResult;
import com.mannschaft.app.payment.repository.PaymentRequestRepository;
import com.mannschaft.app.payment.repository.StripeCustomerRepository;
import com.mannschaft.app.payment.service.CreatePaymentRequestCommand;
import com.mannschaft.app.payment.service.PaymentRequestPayResult;
import com.mannschaft.app.payment.service.PaymentRequestService;
import com.mannschaft.app.payment.service.TeamPaymentAdvanceService;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link PaymentRequestService} の単体テスト（F08.9 P7 第一波）。
 *
 * <p>create 権原 / cancel 状態制約 / superseded 連結 / pay の状態遷移・冪等・READY 未達・charge 引数を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentRequestService 単体テスト（協会請求）")
class PaymentRequestServiceTest {

    @Mock private PaymentRequestRepository paymentRequestRepository;
    @Mock private ConnectAccountRepository connectAccountRepository;
    @Mock private StripeCustomerRepository stripeCustomerRepository;
    @Mock private StripePaymentProvider stripePaymentProvider;
    @Mock private ConnectChargeService connectChargeService;
    @Mock private TeamPaymentAdvanceService teamPaymentAdvanceService;
    @Mock private AccessControlService accessControlService;
    @Mock private com.mannschaft.app.auth.service.AuditLogService auditLogService;

    @InjectMocks
    private PaymentRequestService service;

    private static final Long ORG_ID = 500L;
    private static final Long TEAM_ID = 600L;
    private static final Long ADMIN_USER_ID = 700L;
    private static final UUID PAYEE_CONNECT_ID = UUID.randomUUID();
    private static final UUID ESCROW_ID = UUID.randomUUID();

    private ConnectAccountEntity readyOrgConnectAccount() {
        return ConnectAccountEntity.builder()
                .scopeKind(ScopeKind.ORG)
                .scopeId(ORG_ID)
                .payoutsEnabled(true)
                .build();
    }

    private CreatePaymentRequestCommand createCmd() {
        return new CreatePaymentRequestCommand(
                TEAM_ID, "リーグ参加費", "2026年度", 30000L, "JPY", null,
                LocalDate.of(2026, 7, 31), null);
    }

    @Nested
    @DisplayName("create（発行）")
    class Create {

        @Test
        @DisplayName("正常系: 協会 ADMIN が DRAFT 起票し、着金先 Connect 口座を焼き付ける")
        void 発行成功() {
            ConnectAccountEntity payee = readyOrgConnectAccount();
            payee.setId(PAYEE_CONNECT_ID);
            given(connectAccountRepository.findByScopeKindAndScopeIdAndDeletedAtIsNull(ScopeKind.ORG, ORG_ID))
                    .willReturn(Optional.of(payee));
            given(paymentRequestRepository.save(any(PaymentRequestEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            PaymentRequestEntity result = service.create(ORG_ID, ADMIN_USER_ID, createCmd());

            assertThat(result.getStatus()).isEqualTo(PaymentRequestStatus.DRAFT);
            assertThat(result.getIssuerScopeKind()).isEqualTo(ScopeKind.ORG);
            assertThat(result.getPayerScopeKind()).isEqualTo(ScopeKind.TEAM);
            assertThat(result.getPayerScopeId()).isEqualTo(TEAM_ID);
            assertThat(result.getPayeeConnectAccountId()).isEqualTo(PAYEE_CONNECT_ID);
            assertThat(result.getFaceAmount()).isEqualTo(30000);
            verify(accessControlService).checkAdminOrAbove(ADMIN_USER_ID, ORG_ID, "ORGANIZATION");
        }

        @Test
        @DisplayName("異常系: 協会 ADMIN でない場合 403（権原なし）")
        void 権原なしで403() {
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkAdminOrAbove(ADMIN_USER_ID, ORG_ID, "ORGANIZATION");

            assertThatThrownBy(() -> service.create(ORG_ID, ADMIN_USER_ID, createCmd()))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MembershipBillingErrorCode.PAYMENT_REQUEST_NOT_FOR_THIS_TEAM);
            verify(paymentRequestRepository, never()).save(any());
        }

        @Test
        @DisplayName("異常系: 協会の Connect 口座が無いと CONNECT_NOT_READY（着金先不在）")
        void 着金先不在() {
            given(connectAccountRepository.findByScopeKindAndScopeIdAndDeletedAtIsNull(ScopeKind.ORG, ORG_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.create(ORG_ID, ADMIN_USER_ID, createCmd()))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MembershipBillingErrorCode.PAYMENT_REQUEST_CONNECT_NOT_READY);
        }

        @Test
        @DisplayName("正常系: 再請求は旧 CANCELLED 行の supersededById に新行を指す")
        void 再請求でsuperseded連結() {
            UUID oldId = UUID.randomUUID();
            ConnectAccountEntity payee = readyOrgConnectAccount();
            payee.setId(PAYEE_CONNECT_ID);
            given(connectAccountRepository.findByScopeKindAndScopeIdAndDeletedAtIsNull(ScopeKind.ORG, ORG_ID))
                    .willReturn(Optional.of(payee));
            PaymentRequestEntity old = PaymentRequestEntity.builder()
                    .organizationId(ORG_ID)
                    .status(PaymentRequestStatus.CANCELLED)
                    .build();
            old.setId(oldId);
            given(paymentRequestRepository.findByIdAndDeletedAtIsNull(oldId)).willReturn(Optional.of(old));
            given(paymentRequestRepository.save(any(PaymentRequestEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            CreatePaymentRequestCommand cmd = new CreatePaymentRequestCommand(
                    TEAM_ID, "リーグ参加費（再）", null, 30000L, "JPY", null,
                    LocalDate.of(2026, 7, 31), oldId);

            service.create(ORG_ID, ADMIN_USER_ID, cmd);

            assertThat(old.getSupersededById()).isNotNull();
        }

        @Test
        @DisplayName("異常系: supersede 対象が CANCELLED でないと INVALID_STATUS（循環防止）")
        void 非CANCELLEDをsupersedeで409() {
            UUID oldId = UUID.randomUUID();
            ConnectAccountEntity payee = readyOrgConnectAccount();
            payee.setId(PAYEE_CONNECT_ID);
            given(connectAccountRepository.findByScopeKindAndScopeIdAndDeletedAtIsNull(ScopeKind.ORG, ORG_ID))
                    .willReturn(Optional.of(payee));
            PaymentRequestEntity old = PaymentRequestEntity.builder()
                    .organizationId(ORG_ID)
                    .status(PaymentRequestStatus.SENT)
                    .build();
            old.setId(oldId);
            given(paymentRequestRepository.findByIdAndDeletedAtIsNull(oldId)).willReturn(Optional.of(old));

            CreatePaymentRequestCommand cmd = new CreatePaymentRequestCommand(
                    TEAM_ID, "再", null, 30000L, "JPY", null, LocalDate.of(2026, 7, 31), oldId);

            assertThatThrownBy(() -> service.create(ORG_ID, ADMIN_USER_ID, cmd))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MembershipBillingErrorCode.PAYMENT_REQUEST_INVALID_STATUS);
        }
    }

    @Nested
    @DisplayName("cancel（取消）")
    class Cancel {

        private PaymentRequestEntity requestWithStatus(PaymentRequestStatus status) {
            PaymentRequestEntity r = PaymentRequestEntity.builder()
                    .organizationId(ORG_ID)
                    .payerScopeKind(ScopeKind.TEAM)
                    .payerScopeId(TEAM_ID)
                    .status(status)
                    .build();
            r.setId(UUID.randomUUID());
            return r;
        }

        @Test
        @DisplayName("正常系: DRAFT は CANCELLED へ遷移")
        void DRAFT取消成功() {
            PaymentRequestEntity r = requestWithStatus(PaymentRequestStatus.DRAFT);
            given(paymentRequestRepository.findByIdAndDeletedAtIsNull(r.getId())).willReturn(Optional.of(r));
            given(paymentRequestRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            PaymentRequestEntity result = service.cancel(ORG_ID, r.getId(), ADMIN_USER_ID);

            assertThat(result.getStatus()).isEqualTo(PaymentRequestStatus.CANCELLED);
        }

        @Test
        @DisplayName("異常系: PAID は取消不可（ALREADY_PAID・409）")
        void PAID取消不可() {
            PaymentRequestEntity r = requestWithStatus(PaymentRequestStatus.PAID);
            given(paymentRequestRepository.findByIdAndDeletedAtIsNull(r.getId())).willReturn(Optional.of(r));

            assertThatThrownBy(() -> service.cancel(ORG_ID, r.getId(), ADMIN_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MembershipBillingErrorCode.PAYMENT_REQUEST_ALREADY_PAID);
        }

        @Test
        @DisplayName("異常系: VIEWED は取消不可（INVALID_STATUS・409）")
        void VIEWED取消不可() {
            PaymentRequestEntity r = requestWithStatus(PaymentRequestStatus.VIEWED);
            given(paymentRequestRepository.findByIdAndDeletedAtIsNull(r.getId())).willReturn(Optional.of(r));

            assertThatThrownBy(() -> service.cancel(ORG_ID, r.getId(), ADMIN_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MembershipBillingErrorCode.PAYMENT_REQUEST_INVALID_STATUS);
        }

        @Test
        @DisplayName("異常系: 他テナントの請求は 404 秘匿（IDOR）")
        void 他テナント404() {
            PaymentRequestEntity r = requestWithStatus(PaymentRequestStatus.DRAFT);
            given(paymentRequestRepository.findByIdAndDeletedAtIsNull(r.getId())).willReturn(Optional.of(r));

            assertThatThrownBy(() -> service.cancel(999L, r.getId(), ADMIN_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MembershipBillingErrorCode.PAYMENT_REQUEST_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("pay（支払い・案3 立替課金）")
    class Pay {

        private PaymentRequestEntity payableRequest(PaymentRequestStatus status) {
            PaymentRequestEntity r = PaymentRequestEntity.builder()
                    .organizationId(ORG_ID)
                    .issuerScopeKind(ScopeKind.ORG)
                    .issuerScopeId(ORG_ID)
                    .payerScopeKind(ScopeKind.TEAM)
                    .payerScopeId(TEAM_ID)
                    .payeeConnectAccountId(PAYEE_CONNECT_ID)
                    .faceAmount(30000)
                    .currency("JPY")
                    .status(status)
                    .build();
            r.setId(UUID.randomUUID());
            return r;
        }

        private void stubReadyPayeeAndCustomerAndCharge() {
            ConnectAccountEntity payee = readyOrgConnectAccount();
            payee.setId(PAYEE_CONNECT_ID);
            given(connectAccountRepository.findById(PAYEE_CONNECT_ID)).willReturn(Optional.of(payee));
            given(stripeCustomerRepository.findByUserId(ADMIN_USER_ID)).willReturn(Optional.of(
                    StripeCustomerEntity.builder().userId(ADMIN_USER_ID).stripeCustomerId("cus_admin").build()));
            given(connectChargeService.charge(any(MembershipChargeCommand.class)))
                    .willReturn(new MembershipChargeResult(ESCROW_ID, "secret_x", "pi_x", EscrowStatus.AUTHORIZED));
            given(teamPaymentAdvanceService.createAdvance(
                    anyLong(), anyLong(), anyLong(), any(), any(), anyInt(), any()))
                    .willAnswer(inv -> {
                        TeamPaymentAdvanceEntity a = TeamPaymentAdvanceEntity.builder()
                                .teamId(inv.getArgument(1)).build();
                        a.setId(UUID.randomUUID());
                        return a;
                    });
        }

        @Test
        @DisplayName("正常系: SENT を支払い PAID 化し escrow 連結・立替を起票・charge 引数を検証")
        void 支払い成功とcharge引数() {
            PaymentRequestEntity r = payableRequest(PaymentRequestStatus.SENT);
            given(paymentRequestRepository.findByIdAndDeletedAtIsNull(r.getId())).willReturn(Optional.of(r));
            given(paymentRequestRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            stubReadyPayeeAndCustomerAndCharge();

            PaymentRequestPayResult result = service.pay(TEAM_ID, r.getId(), ADMIN_USER_ID, "idem-1");

            assertThat(r.getStatus()).isEqualTo(PaymentRequestStatus.PAID);
            assertThat(r.getEscrowTransactionId()).isEqualTo(ESCROW_ID);
            assertThat(result.escrowTransactionId()).isEqualTo(ESCROW_ID);
            assertThat(result.clientSecret()).isEqualTo("secret_x");

            ArgumentCaptor<MembershipChargeCommand> captor = ArgumentCaptor.forClass(MembershipChargeCommand.class);
            verify(connectChargeService).charge(captor.capture());
            MembershipChargeCommand cmd = captor.getValue();
            assertThat(cmd.faceAmount()).isEqualTo(30000L);
            assertThat(cmd.payeeConnectAccountId()).isEqualTo(PAYEE_CONNECT_ID);
            assertThat(cmd.payerStripeCustomerId()).isEqualTo("cus_admin");
            assertThat(cmd.payerUserId()).isEqualTo(ADMIN_USER_ID);
            assertThat(cmd.idempotencyKey()).isEqualTo("idem-1");

            verify(teamPaymentAdvanceService).createAdvance(
                    eq(ORG_ID), eq(TEAM_ID), eq(ADMIN_USER_ID), eq(ESCROW_ID), eq(r.getId()),
                    eq(30000), eq("JPY"));
        }

        @Test
        @DisplayName("正常系: OVERDUE でも支払い可能（実運用）")
        void OVERDUEでも支払い可能() {
            PaymentRequestEntity r = payableRequest(PaymentRequestStatus.OVERDUE);
            given(paymentRequestRepository.findByIdAndDeletedAtIsNull(r.getId())).willReturn(Optional.of(r));
            given(paymentRequestRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            stubReadyPayeeAndCustomerAndCharge();

            service.pay(TEAM_ID, r.getId(), ADMIN_USER_ID, "idem-2");

            assertThat(r.getStatus()).isEqualTo(PaymentRequestStatus.PAID);
        }

        @Test
        @DisplayName("異常系: PAID は二重支払い防止（ALREADY_PAID・409）。charge を呼ばない")
        void 二重支払い防止() {
            PaymentRequestEntity r = payableRequest(PaymentRequestStatus.PAID);
            given(paymentRequestRepository.findByIdAndDeletedAtIsNull(r.getId())).willReturn(Optional.of(r));

            assertThatThrownBy(() -> service.pay(TEAM_ID, r.getId(), ADMIN_USER_ID, "idem-3"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MembershipBillingErrorCode.PAYMENT_REQUEST_ALREADY_PAID);
            verify(connectChargeService, never()).charge(any());
        }

        @Test
        @DisplayName("異常系: DRAFT（未配信）は支払い不可（INVALID_STATUS・409）")
        void 未配信は支払い不可() {
            PaymentRequestEntity r = payableRequest(PaymentRequestStatus.DRAFT);
            given(paymentRequestRepository.findByIdAndDeletedAtIsNull(r.getId())).willReturn(Optional.of(r));

            assertThatThrownBy(() -> service.pay(TEAM_ID, r.getId(), ADMIN_USER_ID, "idem-4"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MembershipBillingErrorCode.PAYMENT_REQUEST_INVALID_STATUS);
        }

        @Test
        @DisplayName("異常系: 請求先チーム不一致は 403（NOT_FOR_THIS_TEAM・IDOR）")
        void 請求先チーム不一致で403() {
            PaymentRequestEntity r = payableRequest(PaymentRequestStatus.SENT);
            given(paymentRequestRepository.findByIdAndDeletedAtIsNull(r.getId())).willReturn(Optional.of(r));

            assertThatThrownBy(() -> service.pay(999L, r.getId(), ADMIN_USER_ID, "idem-5"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MembershipBillingErrorCode.PAYMENT_REQUEST_NOT_FOR_THIS_TEAM);
            verify(connectChargeService, never()).charge(any());
        }

        @Test
        @DisplayName("異常系: 着金先が未 READY なら CONNECT_NOT_READY（支払い時に検証・409）")
        void 着金口座未READYで409() {
            PaymentRequestEntity r = payableRequest(PaymentRequestStatus.SENT);
            given(paymentRequestRepository.findByIdAndDeletedAtIsNull(r.getId())).willReturn(Optional.of(r));
            ConnectAccountEntity payee = ConnectAccountEntity.builder()
                    .scopeKind(ScopeKind.ORG).scopeId(ORG_ID).payoutsEnabled(false).build();
            payee.setId(PAYEE_CONNECT_ID);
            given(connectAccountRepository.findById(PAYEE_CONNECT_ID)).willReturn(Optional.of(payee));

            assertThatThrownBy(() -> service.pay(TEAM_ID, r.getId(), ADMIN_USER_ID, "idem-6"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MembershipBillingErrorCode.PAYMENT_REQUEST_CONNECT_NOT_READY);
            verify(connectChargeService, never()).charge(any());
        }

        @Test
        @DisplayName("異常系: チーム ADMIN でない場合 403（NOT_FOR_THIS_TEAM）。charge を呼ばない")
        void 非ADMINで403() {
            PaymentRequestEntity r = payableRequest(PaymentRequestStatus.SENT);
            given(paymentRequestRepository.findByIdAndDeletedAtIsNull(r.getId())).willReturn(Optional.of(r));
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkAdminOrAbove(ADMIN_USER_ID, TEAM_ID, "TEAM");

            assertThatThrownBy(() -> service.pay(TEAM_ID, r.getId(), ADMIN_USER_ID, "idem-7"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MembershipBillingErrorCode.PAYMENT_REQUEST_NOT_FOR_THIS_TEAM);
            verify(connectChargeService, never()).charge(any());
        }

        @Test
        @DisplayName("異常系: 請求が見つからない場合 404")
        void 請求不在404() {
            UUID id = UUID.randomUUID();
            given(paymentRequestRepository.findByIdAndDeletedAtIsNull(id)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.pay(TEAM_ID, id, ADMIN_USER_ID, "idem-8"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MembershipBillingErrorCode.PAYMENT_REQUEST_NOT_FOUND);
        }
    }
}
