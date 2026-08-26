package com.mannschaft.app.payment.escrow;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.payment.FeePolicyResolver;
import com.mannschaft.app.payment.PaymentFeeCalculator;
import com.mannschaft.app.payment.connect.ConnectAccountEntity;
import com.mannschaft.app.payment.connect.ConnectAccountRepository;
import com.mannschaft.app.payment.connect.ConnectPaymentErrorCode;
import com.mannschaft.app.payment.connect.OnboardingStatus;
import com.mannschaft.app.payment.connect.PayeeScopeResolver;
import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F22.1 謝礼決済 フォロー Wave A: {@link ConnectChargeService#listReceivedEscrows}（受取側エスクロー一覧）単体テスト。
 *
 * <p>test-first。検証: USER 本人→自分の受取一覧（clientSecret 非含有）/ TEAM ADMIN→当該 TEAM 受取一覧 /
 * status フィルタ伝播 / pagination / 他人 USER scope→403（IDOR）/ 非 ADMIN TEAM→403 / 受取 Connect 口座未登録→空ページ /
 * 複数件＋返金累計集計。Stripe 実通信は呼ばない（読み取り専用ゆえ {@link StripePaymentProvider} に触れない）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConnectChargeService 受取側一覧 単体テスト（フォロー Wave A）")
class ConnectChargeReceivedListTest {

    @Mock private EscrowTransactionRepository escrowTransactionRepository;
    @Mock private ConnectAccountRepository connectAccountRepository;
    @Mock private StripePaymentProvider stripePaymentProvider;
    @Mock private AccessControlService accessControlService;
    @Mock private LedgerEntryRepository ledgerEntryRepository;
    @Mock private RefundRepository refundRepository;
    @Mock private FeePolicyResolver feePolicyResolver;

    private final PaymentFeeCalculator feeCalculator = new PaymentFeeCalculator();

    private static final Long ACTOR_USER_ID = 999L;
    private static final Long TEAM_ID = 55L;
    private static final UUID PAYEE_ACCOUNT_ID = UUID.fromString("019607a0-0000-7000-8000-0000000000aa");
    private static final UUID ESCROW_ID = UUID.fromString("019607a0-0000-7000-8000-000000000099");

    private ConnectChargeService service() {
        return new ConnectChargeService(
                escrowTransactionRepository, connectAccountRepository,
                feeCalculator, stripePaymentProvider, accessControlService, ledgerEntryRepository,
                refundRepository, new PayeeScopeResolver(), feePolicyResolver,
                org.mockito.Mockito.mock(com.mannschaft.app.payment.recovery.FeeRecoveryBalanceRepository.class));
    }

    private ConnectAccountEntity payeeAccount(ScopeKind scopeKind, Long scopeId) {
        return ConnectAccountEntity.builder()
                .scopeKind(scopeKind).scopeId(scopeId)
                .stripeAccountId("acct_payee").onboardingStatus(OnboardingStatus.READY)
                .chargesEnabled(true).payoutsEnabled(true).country("JP").defaultCurrency("JPY")
                .build();
    }

    private EscrowTransactionEntity captured() {
        return EscrowTransactionEntity.builder()
                .sourceKind(EscrowSourceKind.RECRUITMENT).sourceId(100L).sourceParticipantId(200L)
                .captureMode(EscrowCaptureMode.MANUAL)
                .payerScopeKind(ScopeKind.USER).payerScopeId(1234L)
                .payeeKind(ScopeKind.TEAM).payeeConnectAccountId(PAYEE_ACCOUNT_ID)
                .faceAmount(10_000L).amount(10_250L).applicationFeeAmount(500L)
                .currency("JPY").feePolicyKey("DEFAULT").status(EscrowStatus.CAPTURED)
                .createdAt(LocalDateTime.of(2026, 6, 11, 12, 0, 0))
                .build();
    }

    @Test
    @DisplayName("USER 本人: 自分の受取一覧を返す（scope 認可は本人照合のみ・AccessControlService 不使用）")
    void userSelf_returnsOwnReceived() {
        ConnectChargeService svc = service();
        given(connectAccountRepository.findByScopeKindAndScopeIdAndDeletedAtIsNull(
                ScopeKind.USER, ACTOR_USER_ID))
                .willReturn(Optional.of(payeeAccount(ScopeKind.USER, ACTOR_USER_ID)));
        Pageable pageable = PageRequest.of(0, 20);
        given(escrowTransactionRepository.findByPayeeConnectAccountIdOrderByCreatedAtDesc(
                any(), eq(pageable)))
                .willReturn(new PageImpl<>(List.of(captured()), pageable, 1));
        given(refundRepository.findByEscrowTransactionId(any())).willReturn(List.of());

        Page<ConnectChargeService.ReceivedEscrow> result =
                svc.listReceivedEscrows(ScopeKind.USER, ACTOR_USER_ID, null, ACTOR_USER_ID, pageable);

        assertThat(result.getTotalElements()).isEqualTo(1L);
        ConnectChargeService.ReceivedEscrow row = result.getContent().get(0);
        assertThat(row.status()).isEqualTo(EscrowStatus.CAPTURED);
        assertThat(row.faceAmount()).isEqualTo(10_000L);
        assertThat(row.chargeAmount()).isEqualTo(10_250L);
        assertThat(row.applicationFeeAmount()).isEqualTo(500L);
        assertThat(row.refundedAmount()).isZero();
        // USER 本人照合では AccessControlService を一切呼ばない。
        verify(accessControlService, never()).checkPermission(any(), any(), any(), any());
        verify(accessControlService, never()).checkAdminOrHasPermission(any(), any(), any(), any());
    }

    @Test
    @DisplayName("TEAM ADMIN: checkPermission 通過→当該 TEAM の受取一覧＋status フィルタ伝播＋返金累計集計")
    void teamAdmin_withStatusFilter_aggregatesRefund() {
        ConnectChargeService svc = service();
        given(connectAccountRepository.findByScopeKindAndScopeIdAndDeletedAtIsNull(
                ScopeKind.TEAM, TEAM_ID))
                .willReturn(Optional.of(payeeAccount(ScopeKind.TEAM, TEAM_ID)));
        Pageable pageable = PageRequest.of(0, 20);
        EscrowTransactionEntity e = captured();
        e.setStatus(EscrowStatus.PARTIALLY_REFUNDED);
        given(escrowTransactionRepository.findByPayeeConnectAccountIdAndStatusOrderByCreatedAtDesc(
                any(), eq(EscrowStatus.PARTIALLY_REFUNDED), eq(pageable)))
                .willReturn(new PageImpl<>(List.of(e), pageable, 1));
        RefundEntity ok = RefundEntity.builder().escrowTransactionId(ESCROW_ID)
                .stripeRefundId("re_1").amount(3_000L).currency("JPY").reason("REQUESTED_BY_CUSTOMER")
                .status(RefundStatus.SUCCEEDED).build();
        RefundEntity failed = RefundEntity.builder().escrowTransactionId(ESCROW_ID)
                .stripeRefundId("re_2").amount(9_999L).currency("JPY").reason("REQUESTED_BY_CUSTOMER")
                .status(RefundStatus.FAILED).build();
        given(refundRepository.findByEscrowTransactionId(any())).willReturn(List.of(ok, failed));

        Page<ConnectChargeService.ReceivedEscrow> result = svc.listReceivedEscrows(
                ScopeKind.TEAM, TEAM_ID, EscrowStatus.PARTIALLY_REFUNDED, ACTOR_USER_ID, pageable);

        assertThat(result.getContent()).hasSize(1);
        // FAILED は残額を消費しない（除外）→ 返金累計は SUCCEEDED の 3,000 のみ。
        assertThat(result.getContent().get(0).refundedAmount()).isEqualTo(3_000L);
        verify(accessControlService).checkPermission(
                eq(ACTOR_USER_ID), eq(TEAM_ID), eq("TEAM"), any());
    }

    @Test
    @DisplayName("USER 他人 scope: 本人と異なる scopeId→403（IDOR・Connect 口座も引かない）")
    void userOther_forbidden() {
        ConnectChargeService svc = service();
        Pageable pageable = PageRequest.of(0, 20);

        assertThatThrownBy(() -> svc.listReceivedEscrows(
                ScopeKind.USER, 1111L, null, ACTOR_USER_ID, pageable))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ConnectPaymentErrorCode.PAYMENT_FORBIDDEN);
        verify(connectAccountRepository, never())
                .findByScopeKindAndScopeIdAndDeletedAtIsNull(any(), any());
    }

    @Test
    @DisplayName("TEAM 非 ADMIN: checkPermission が認可エラー→403 へ正規化")
    void teamNonAdmin_forbidden() {
        ConnectChargeService svc = service();
        willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .given(accessControlService).checkPermission(eq(ACTOR_USER_ID), eq(TEAM_ID), eq("TEAM"), any());
        Pageable pageable = PageRequest.of(0, 20);

        assertThatThrownBy(() -> svc.listReceivedEscrows(
                ScopeKind.TEAM, TEAM_ID, null, ACTOR_USER_ID, pageable))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ConnectPaymentErrorCode.PAYMENT_FORBIDDEN);
    }

    @Test
    @DisplayName("受取 Connect 口座未登録（受取実績ゼロ）: 認可通過後に空ページを返す")
    void noConnectAccount_returnsEmptyPage() {
        ConnectChargeService svc = service();
        given(connectAccountRepository.findByScopeKindAndScopeIdAndDeletedAtIsNull(
                ScopeKind.TEAM, TEAM_ID))
                .willReturn(Optional.empty());
        Pageable pageable = PageRequest.of(0, 20);

        Page<ConnectChargeService.ReceivedEscrow> result = svc.listReceivedEscrows(
                ScopeKind.TEAM, TEAM_ID, null, ACTOR_USER_ID, pageable);

        assertThat(result.getTotalElements()).isZero();
        assertThat(result.getContent()).isEmpty();
        verify(escrowTransactionRepository, never())
                .findByPayeeConnectAccountIdOrderByCreatedAtDesc(any(), any());
    }
}
