package com.mannschaft.app.payment.escrow;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.payment.PaymentFeeCalculator;
import com.mannschaft.app.payment.connect.ConnectAccountEntity;
import com.mannschaft.app.payment.connect.ConnectAccountRepository;
import com.mannschaft.app.payment.connect.PayeeScopeResolver;
import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;

/**
 * F03.11.1 受取先側の精算管理者判定（{@link ConnectChargeService#isPayeeSettlementManager}）の試練。
 *
 * <p>設計書 §10.2 の受取先 3 種（TEAM / ORG / 個人）について、<b>肯定側と否定側を対で</b>起こす。
 * 肯定側だけを書くと「判定が常に true」でも緑になってしまうためである（§11.1）。
 * AC-20 / AC-27 / AC-28 の土台を担う。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("F03.11.1 ConnectChargeService.isPayeeSettlementManager 試練")
class ConnectChargeServiceIsPayeeSettlementManagerTest {

    @Mock private EscrowTransactionRepository escrowTransactionRepository;
    @Mock private ConnectAccountRepository connectAccountRepository;
    @Mock private StripePaymentProvider stripePaymentProvider;
    @Mock private AccessControlService accessControlService;
    @Mock private LedgerEntryRepository ledgerEntryRepository;
    @Mock private RefundRepository refundRepository;
    @Mock private com.mannschaft.app.payment.FeePolicyResolver feePolicyResolver;
    @Mock private com.mannschaft.app.payment.recovery.FeeRecoveryBalanceRepository feeRecoveryBalanceRepository;

    private final PaymentFeeCalculator feeCalculator = new PaymentFeeCalculator();

    private static final UUID ESCROW_ID = UUID.fromString("019607a0-0000-7000-8000-0000000f0312");
    private static final UUID PAYEE_ACCOUNT_ID = UUID.fromString("019607a0-0000-7000-8000-0000000f0313");
    private static final Long LISTING_ID = 100L;
    private static final Long PARTICIPANT_ID = 200L;
    private static final Long PAYEE_SCOPE_ID = 10L;
    private static final Long ACTOR_ID = 55L;

    private ConnectChargeService service() {
        return new ConnectChargeService(
                escrowTransactionRepository, connectAccountRepository,
                feeCalculator, stripePaymentProvider, accessControlService, ledgerEntryRepository,
                refundRepository, new PayeeScopeResolver(), feePolicyResolver,
                feeRecoveryBalanceRepository);
    }

    private void givenPayee(ScopeKind payeeKind, Long payeeScopeId) {
        EscrowTransactionEntity e = EscrowTransactionEntity.builder()
                .sourceKind(EscrowSourceKind.RECRUITMENT)
                .sourceId(LISTING_ID).sourceParticipantId(PARTICIPANT_ID)
                .captureMode(EscrowCaptureMode.MANUAL)
                .payerScopeKind(ScopeKind.USER).payerScopeId(999L)
                .payeeKind(payeeKind).payeeConnectAccountId(PAYEE_ACCOUNT_ID)
                .faceAmount(10_000L).amount(10_250L).applicationFeeAmount(250L)
                .currency("JPY").status(EscrowStatus.AUTHORIZED).stripePaymentIntentId("pi_abc")
                .build();
        e.setId(ESCROW_ID);
        given(escrowTransactionRepository.findBySourceKindAndSourceIdAndSourceParticipantId(
                EscrowSourceKind.RECRUITMENT, LISTING_ID, PARTICIPANT_ID)).willReturn(Optional.of(e));
        given(connectAccountRepository.findById(PAYEE_ACCOUNT_ID)).willReturn(Optional.of(
                ConnectAccountEntity.builder()
                        .scopeKind(payeeKind)
                        .scopeId(payeeScopeId)
                        .stripeAccountId("acct_abc")
                        .payoutsEnabled(true)
                        .chargesEnabled(true)
                        .build()));
    }

    private boolean judge(ConnectChargeService svc, Long actorUserId) {
        return svc.isPayeeSettlementManager(
                EscrowSourceKind.RECRUITMENT, LISTING_ID, PARTICIPANT_ID, actorUserId);
    }

    @Test
    @DisplayName("AC-27(肯定): 受取先が TEAM のとき、その TEAM の支払い管理権限者は true")
    void ac27_teamPayee_managerOfThatTeamIsAccepted() {
        ConnectChargeService svc = service();
        givenPayee(ScopeKind.TEAM, PAYEE_SCOPE_ID);
        willDoNothing().given(accessControlService)
                .checkPermission(eq(ACTOR_ID), eq(PAYEE_SCOPE_ID), anyString(), anyString());

        assertThat(judge(svc, ACTOR_ID)).isTrue();
    }

    @Test
    @DisplayName("AC-27(否定): 受取先が TEAM のとき、無関係な TEAM の管理者は false")
    void ac27_teamPayee_managerOfOtherTeamIsRejected() {
        ConnectChargeService svc = service();
        givenPayee(ScopeKind.TEAM, PAYEE_SCOPE_ID);
        willThrow(new BusinessException(CommonErrorCode.COMMON_002)).given(accessControlService)
                .checkPermission(eq(ACTOR_ID), eq(PAYEE_SCOPE_ID), anyString(), anyString());

        assertThat(judge(svc, ACTOR_ID)).isFalse();
    }

    @Test
    @DisplayName("AC-20(肯定): 受取先が ORG のとき、その組織の管理者は true")
    void ac20_orgPayee_adminOfThatOrgIsAccepted() {
        ConnectChargeService svc = service();
        givenPayee(ScopeKind.ORG, PAYEE_SCOPE_ID);
        willDoNothing().given(accessControlService)
                .checkAdminOrHasPermission(eq(ACTOR_ID), eq(PAYEE_SCOPE_ID), anyString(), anyString());

        assertThat(judge(svc, ACTOR_ID)).isTrue();
    }

    @Test
    @DisplayName("AC-20(否定): 受取先が ORG のとき、別テナントの管理者は false")
    void ac20_orgPayee_adminOfOtherTenantIsRejected() {
        ConnectChargeService svc = service();
        givenPayee(ScopeKind.ORG, PAYEE_SCOPE_ID);
        willThrow(new BusinessException(CommonErrorCode.COMMON_002)).given(accessControlService)
                .checkAdminOrHasPermission(eq(ACTOR_ID), eq(PAYEE_SCOPE_ID), anyString(), anyString());

        assertThat(judge(svc, ACTOR_ID)).isFalse();
    }

    @Test
    @DisplayName("AC-28(肯定): 受取先が個人（payeeKind=USER）のとき、その本人は true")
    void ac28_userPayee_theUserThemselfIsAccepted() {
        ConnectChargeService svc = service();
        // 既存 authorizePayeeAdmin は個人受領を対象外にしているため、本判定で新たに定義する（§10.2）。
        givenPayee(ScopeKind.USER, ACTOR_ID);

        assertThat(judge(svc, ACTOR_ID)).isTrue();
    }

    @Test
    @DisplayName("AC-28(否定): 受取先が個人のとき、他人は false")
    void ac28_userPayee_someoneElseIsRejected() {
        ConnectChargeService svc = service();
        givenPayee(ScopeKind.USER, 999_999L);

        assertThat(judge(svc, ACTOR_ID)).isFalse();
    }

    @Test
    @DisplayName("与信が引けない場合は受取先を特定できないため false（運営のみが免除できる状態）")
    void missingEscrow_returnsFalse() {
        ConnectChargeService svc = service();
        given(escrowTransactionRepository.findBySourceKindAndSourceIdAndSourceParticipantId(
                EscrowSourceKind.RECRUITMENT, LISTING_ID, PARTICIPANT_ID)).willReturn(Optional.empty());

        assertThat(judge(svc, ACTOR_ID)).isFalse();
    }
}
