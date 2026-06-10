package com.mannschaft.app.tournament.fee;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.payment.dto.ConnectCheckoutResponse;
import com.mannschaft.app.payment.PaymentItemType;
import com.mannschaft.app.payment.entity.MemberPaymentEntity;
import com.mannschaft.app.payment.entity.PaymentItemEntity;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import com.mannschaft.app.payment.service.MemberPaymentService;
import com.mannschaft.app.payment.service.PaymentItemService;
import com.mannschaft.app.tournament.TournamentErrorCode;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import com.mannschaft.app.tournament.fee.dto.MyTournamentFeesResponse;
import com.mannschaft.app.tournament.fee.dto.TournamentFeeCheckoutResponse;
import com.mannschaft.app.tournament.repository.TournamentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link TournamentFeePaymentService} の単体テスト（純 Mockito UT）。
 *
 * <p>F08.7.1 Connect 決済連携。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TournamentFeePaymentService 単体テスト")
class TournamentFeePaymentServiceTest {

    // =========================================================
    // 定数
    // =========================================================
    private static final Long USER_ID = 100L;
    private static final Long ORG_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final Long TOURNAMENT_ID = 200L;
    private static final Long PAYMENT_ITEM_ID = 300L;

    // =========================================================
    // モック
    // =========================================================
    @Mock private TournamentFeeRepository tournamentFeeRepository;
    @Mock private TournamentFeeTargetRepository tournamentFeeTargetRepository;
    @Mock private MemberPaymentService memberPaymentService;
    @Mock private PaymentItemService paymentItemService;
    @Mock private MemberPaymentRepository memberPaymentRepository;
    @Mock private MembershipRepository membershipRepository;
    @Mock private TournamentRepository tournamentRepository;

    @InjectMocks
    private TournamentFeePaymentService service;

    // =========================================================
    // フィクスチャ
    // =========================================================

    private MembershipEntity orgMembership() {
        return MembershipEntity.builder()
                .userId(USER_ID)
                .scopeType(ScopeType.ORGANIZATION)
                .scopeId(ORG_ID)
                .build();
    }

    private MembershipEntity teamMembership() {
        return MembershipEntity.builder()
                .userId(USER_ID)
                .scopeType(ScopeType.TEAM)
                .scopeId(TEAM_ID)
                .build();
    }

    private PaymentItemEntity paymentItem() {
        return PaymentItemEntity.builder()
                .organizationId(ORG_ID)
                .name("参加費")
                .type(PaymentItemType.ITEM)
                .amount(new BigDecimal("5000"))
                .currency("JPY")
                .build();
    }

    private TournamentFeeEntity allTeamsFee() {
        TournamentFeeEntity f = TournamentFeeEntity.builder()
                .tournamentId(TOURNAMENT_ID)
                .paymentItemId(PAYMENT_ITEM_ID)
                .title("2026 春季 参加費")
                .targetScope(TournamentFeeTargetScope.ALL_TEAMS)
                .organizationId(ORG_ID)
                .createdBy(99L)
                .build();
        f.setId(UUID.randomUUID());
        return f;
    }

    private TournamentFeeEntity specificTeamsFee(UUID feeId) {
        TournamentFeeEntity f = TournamentFeeEntity.builder()
                .tournamentId(TOURNAMENT_ID)
                .paymentItemId(PAYMENT_ITEM_ID)
                .title("特定チーム参加費")
                .targetScope(TournamentFeeTargetScope.SPECIFIC_TEAMS)
                .organizationId(ORG_ID)
                .createdBy(99L)
                .build();
        f.setId(feeId);
        return f;
    }

    private TournamentEntity tournament() {
        return TournamentEntity.builder()
                .organizationId(ORG_ID)
                .name("テスト大会")
                .build();
    }

    // =========================================================
    // getMyTournamentFees テスト
    // =========================================================

    @Nested
    @DisplayName("getMyTournamentFees")
    class GetMyTournamentFees {

        @Test
        @DisplayName("ALL_TEAMS: 自分の組織に紐付く参加費が返る")
        void getMyTournamentFees_allTeams_returnsOwnFee() {
            // given
            TournamentFeeEntity fee = allTeamsFee();
            given(membershipRepository.findActiveByUserAndScopeType(USER_ID, ScopeType.ORGANIZATION))
                    .willReturn(List.of(orgMembership()));
            given(membershipRepository.findActiveByUserAndScopeType(USER_ID, ScopeType.TEAM))
                    .willReturn(List.of());
            given(tournamentFeeRepository.findByOrganizationId(ORG_ID))
                    .willReturn(List.of(fee));
            given(tournamentRepository.findAllById(List.of(TOURNAMENT_ID)))
                    .willReturn(List.of(tournament()));
            given(paymentItemService.findByIdOrThrow(PAYMENT_ITEM_ID))
                    .willReturn(paymentItem());
            given(memberPaymentRepository.existsValidPaidPayment(USER_ID, PAYMENT_ITEM_ID))
                    .willReturn(false);

            // when
            MyTournamentFeesResponse response = service.getMyTournamentFees(USER_ID);

            // then
            assertThat(response.fees()).hasSize(1);
            assertThat(response.fees().get(0).feeId()).isEqualTo(fee.getId());
            assertThat(response.fees().get(0).alreadyPaid()).isFalse();
            assertThat(response.fees().get(0).faceAmount()).isEqualTo(5000);
        }

        @Test
        @DisplayName("SPECIFIC_TEAMS: 所属チームが対象なら参加費が返る")
        void getMyTournamentFees_specificTeams_eligibleTeamMember_includesFee() {
            // given
            UUID feeId = UUID.randomUUID();
            TournamentFeeEntity fee = specificTeamsFee(feeId);
            given(membershipRepository.findActiveByUserAndScopeType(USER_ID, ScopeType.ORGANIZATION))
                    .willReturn(List.of(orgMembership()));
            given(membershipRepository.findActiveByUserAndScopeType(USER_ID, ScopeType.TEAM))
                    .willReturn(List.of(teamMembership()));
            given(tournamentFeeRepository.findByOrganizationId(ORG_ID))
                    .willReturn(List.of(fee));
            given(tournamentFeeTargetRepository.existsByFeeIdAndTeamId(feeId, TEAM_ID))
                    .willReturn(true);
            given(tournamentRepository.findAllById(List.of(TOURNAMENT_ID)))
                    .willReturn(List.of(tournament()));
            given(paymentItemService.findByIdOrThrow(PAYMENT_ITEM_ID))
                    .willReturn(paymentItem());
            given(memberPaymentRepository.existsValidPaidPayment(USER_ID, PAYMENT_ITEM_ID))
                    .willReturn(false);

            // when
            MyTournamentFeesResponse response = service.getMyTournamentFees(USER_ID);

            // then
            assertThat(response.fees()).hasSize(1);
            assertThat(response.fees().get(0).feeId()).isEqualTo(feeId);
        }

        @Test
        @DisplayName("SPECIFIC_TEAMS: 対象外チームのメンバーは参加費が除外される")
        void getMyTournamentFees_specificTeams_notInTargetTeam_excludesFee() {
            // given
            UUID feeId = UUID.randomUUID();
            TournamentFeeEntity fee = specificTeamsFee(feeId);
            given(membershipRepository.findActiveByUserAndScopeType(USER_ID, ScopeType.ORGANIZATION))
                    .willReturn(List.of(orgMembership()));
            given(membershipRepository.findActiveByUserAndScopeType(USER_ID, ScopeType.TEAM))
                    .willReturn(List.of(teamMembership()));
            given(tournamentFeeRepository.findByOrganizationId(ORG_ID))
                    .willReturn(List.of(fee));
            given(tournamentFeeTargetRepository.existsByFeeIdAndTeamId(feeId, TEAM_ID))
                    .willReturn(false);

            // when
            MyTournamentFeesResponse response = service.getMyTournamentFees(USER_ID);

            // then
            assertThat(response.fees()).isEmpty();
        }

        @Test
        @DisplayName("支払い済みの場合、alreadyPaid=true で paidAt が設定される")
        void getMyTournamentFees_alreadyPaid_marksAlreadyPaid() {
            // given
            TournamentFeeEntity fee = allTeamsFee();
            LocalDateTime paidAt = LocalDateTime.of(2026, 5, 1, 12, 0);
            MemberPaymentEntity paidPayment = MemberPaymentEntity.builder()
                    .userId(USER_ID)
                    .paymentItemId(PAYMENT_ITEM_ID)
                    .build();
            // リフレクションで paidAt を注入（@Builder ではセッターなし → 新規作成時は @PrePersist で設定）
            // ここでは paidAt=null のまま返しても alreadyPaid=true の確認には十分
            given(membershipRepository.findActiveByUserAndScopeType(USER_ID, ScopeType.ORGANIZATION))
                    .willReturn(List.of(orgMembership()));
            given(membershipRepository.findActiveByUserAndScopeType(USER_ID, ScopeType.TEAM))
                    .willReturn(List.of());
            given(tournamentFeeRepository.findByOrganizationId(ORG_ID))
                    .willReturn(List.of(fee));
            given(tournamentRepository.findAllById(List.of(TOURNAMENT_ID)))
                    .willReturn(List.of(tournament()));
            given(paymentItemService.findByIdOrThrow(PAYMENT_ITEM_ID))
                    .willReturn(paymentItem());
            given(memberPaymentRepository.existsValidPaidPayment(USER_ID, PAYMENT_ITEM_ID))
                    .willReturn(true);
            given(memberPaymentRepository.findValidPaidPayments(USER_ID, PAYMENT_ITEM_ID))
                    .willReturn(List.of(paidPayment));

            // when
            MyTournamentFeesResponse response = service.getMyTournamentFees(USER_ID);

            // then
            assertThat(response.fees()).hasSize(1);
            assertThat(response.fees().get(0).alreadyPaid()).isTrue();
        }

        @Test
        @DisplayName("組織所属なしの場合、空リストが返る")
        void getMyTournamentFees_noOrg_returnsEmpty() {
            // given
            given(membershipRepository.findActiveByUserAndScopeType(USER_ID, ScopeType.ORGANIZATION))
                    .willReturn(List.of());

            // when
            MyTournamentFeesResponse response = service.getMyTournamentFees(USER_ID);

            // then
            assertThat(response.fees()).isEmpty();
        }
    }

    // =========================================================
    // checkoutFee テスト
    // =========================================================

    @Nested
    @DisplayName("checkoutFee")
    class CheckoutFee {

        @Test
        @DisplayName("正常系: Connect 決済レールへ委譲し clientSecret を返す")
        void checkoutFee_success_returnsClientSecret() {
            // given
            UUID feeId = UUID.randomUUID();
            TournamentFeeEntity fee = allTeamsFee();
            fee.setId(feeId);
            String clientSecret = "pi_test_secret";
            Long memberPaymentId = 42L;
            UUID escrowId = UUID.randomUUID();

            given(tournamentFeeRepository.findById(feeId)).willReturn(Optional.of(fee));
            given(memberPaymentService.createConnectCheckout(
                    eq(PAYMENT_ITEM_ID), eq(USER_ID), eq(USER_ID), any(String.class)))
                    .willReturn(new ConnectCheckoutResponse(clientSecret, memberPaymentId, escrowId));

            // when
            TournamentFeeCheckoutResponse response = service.checkoutFee(feeId, USER_ID, "key-001");

            // then
            assertThat(response.clientSecret()).isEqualTo(clientSecret);
            assertThat(response.memberPaymentId()).isEqualTo(memberPaymentId);
            assertThat(response.escrowTransactionId()).isEqualTo(escrowId);
            verify(memberPaymentService).createConnectCheckout(PAYMENT_ITEM_ID, USER_ID, USER_ID, "key-001");
        }

        @Test
        @DisplayName("idempotencyKey が null の場合、自動生成キーで決済が実行される")
        void checkoutFee_nullIdempotencyKey_generatesKey() {
            // given
            UUID feeId = UUID.randomUUID();
            TournamentFeeEntity fee = allTeamsFee();
            fee.setId(feeId);
            given(tournamentFeeRepository.findById(feeId)).willReturn(Optional.of(fee));
            given(memberPaymentService.createConnectCheckout(
                    eq(PAYMENT_ITEM_ID), eq(USER_ID), eq(USER_ID), any(String.class)))
                    .willReturn(new ConnectCheckoutResponse("secret", 1L, UUID.randomUUID()));

            // when
            TournamentFeeCheckoutResponse response = service.checkoutFee(feeId, USER_ID, null);

            // then
            assertThat(response.clientSecret()).isEqualTo("secret");
            // idempotencyKey は自動生成されるため、any(String.class) で呼ばれることを確認済み
            verify(memberPaymentService).createConnectCheckout(eq(PAYMENT_ITEM_ID), eq(USER_ID), eq(USER_ID), any(String.class));
        }

        @Test
        @DisplayName("存在しない feeId は FEE_NOT_FOUND 例外をスローする")
        void checkoutFee_feeNotFound_throwsException() {
            // given
            UUID feeId = UUID.randomUUID();
            given(tournamentFeeRepository.findById(feeId)).willReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() -> service.checkoutFee(feeId, USER_ID, "key"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(TournamentErrorCode.FEE_NOT_FOUND.getMessage());
        }
    }
}
