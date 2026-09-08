package com.mannschaft.app.gdpr;

import com.mannschaft.app.auth.event.WithdrawalCancelledEvent;
import com.mannschaft.app.auth.event.WithdrawalRequestedEvent;
import com.mannschaft.app.billing.BillingContractService;
import com.mannschaft.app.billing.BillingContractService.HandoverTargetContract;
import com.mannschaft.app.billing.BillingPayerHandoverService;
import com.mannschaft.app.billing.ContractStatus;
import com.mannschaft.app.billing.EntitlementErrorCode;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.gdpr.service.WithdrawalStripeHandler;
import com.mannschaft.app.payment.service.MembershipSubscriptionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 柱③-B PR-3（CMP-260901-1538）: {@link WithdrawalStripeHandler} の単体テスト。
 *
 * <p>設計書 §1.3・§5.1・§6。是正前はスタブ（{@code log.warn("未実装")}）であり
 * <b>払い手が退会しても課金が止まらなかった</b>。本テストは 2 系統（継続課金の期末解約 / TEAM・ORG 契約の
 * 引継要求発行）がいずれも結線され、片方の失敗がもう片方を落とさないことを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WithdrawalStripeHandler 単体テスト（柱③-B PR-3）")
class WithdrawalStripeHandlerTest {

    private static final Long USER_ID = 5001L;

    @Mock
    private MembershipSubscriptionService membershipSubscriptionService;
    @Mock
    private BillingContractService billingContractService;
    @Mock
    private BillingPayerHandoverService billingPayerHandoverService;

    @InjectMocks
    private WithdrawalStripeHandler handler;

    private static WithdrawalRequestedEvent event() {
        return new WithdrawalRequestedEvent(USER_ID, "user@example.com");
    }

    private static HandoverTargetContract target(UUID contractId, Long scopeId) {
        return new HandoverTargetContract(
                contractId, EntitlementScopeKind.TEAM, scopeId, ContractStatus.ACTIVE, "sub_" + scopeId);
    }

    @Nested
    @DisplayName("① membership_subscriptions の期末解約（AC-13）")
    class MembershipCancellation {

        @Test
        @DisplayName("正常系: 退会イベントで payer の継続課金一括解約が呼ばれる")
        void 正常_継続課金の一括期末解約が呼ばれる() {
            given(membershipSubscriptionService.cancelAllForPayerOnWithdrawal(anyLong()))
                    .willReturn(List.of(UUID.randomUUID(), UUID.randomUUID()));
            given(billingContractService.findHandoverTargetContractsForPayer(anyLong()))
                    .willReturn(List.of());

            handler.handleWithdrawal(event());

            verify(membershipSubscriptionService).cancelAllForPayerOnWithdrawal(USER_ID);
        }

        @Test
        @DisplayName("異常系: 継続課金解約が失敗しても例外は伝播せず、引継検出は続行される")
        void 異常_継続課金解約の失敗で引継処理を止めない() {
            given(membershipSubscriptionService.cancelAllForPayerOnWithdrawal(anyLong()))
                    .willThrow(new IllegalStateException("Stripe 障害"));
            given(billingContractService.findHandoverTargetContractsForPayer(anyLong()))
                    .willReturn(List.of());

            assertThatCode(() -> handler.handleWithdrawal(event())).doesNotThrowAnyException();
            verify(billingContractService).findHandoverTargetContractsForPayer(USER_ID);
        }
    }

    @Nested
    @DisplayName("② TEAM/ORG 契約の引継要求（§5.1・§5.2）")
    class HandoverRequests {

        @Test
        @DisplayName("正常系: 検出した契約ごとに引継要求が発行される")
        void 正常_検出した契約ごとに引継要求を発行する() {
            UUID c1 = UUID.randomUUID();
            UUID c2 = UUID.randomUUID();
            given(billingContractService.findHandoverTargetContractsForPayer(anyLong()))
                    .willReturn(List.of(target(c1, 71L), target(c2, 72L)));

            handler.handleWithdrawal(event());

            verify(billingPayerHandoverService).requestHandoverForWithdrawal(c1, USER_ID);
            verify(billingPayerHandoverService).requestHandoverForWithdrawal(c2, USER_ID);
        }

        @Test
        @DisplayName("正常系: 引継対象が無ければ引継要求は発行されない")
        void 正常_対象なしなら引継要求を発行しない() {
            given(billingContractService.findHandoverTargetContractsForPayer(anyLong()))
                    .willReturn(List.of());

            handler.handleWithdrawal(event());

            verify(billingPayerHandoverService, never())
                    .requestHandoverForWithdrawal(any(), anyLong());
        }

        @Test
        @DisplayName("異常系: 1件が候補不在（BusinessException）でも他契約の引継要求は発行される")
        void 異常_候補不在の1件で他契約を巻き添えにしない() {
            UUID failing = UUID.randomUUID();
            UUID ok = UUID.randomUUID();
            given(billingContractService.findHandoverTargetContractsForPayer(anyLong()))
                    .willReturn(List.of(target(failing, 81L), target(ok, 82L)));
            given(billingPayerHandoverService.requestHandoverForWithdrawal(eq(failing), anyLong()))
                    .willThrow(new BusinessException(EntitlementErrorCode.HANDOVER_NO_CANDIDATE));

            assertThatCode(() -> handler.handleWithdrawal(event())).doesNotThrowAnyException();

            verify(billingPayerHandoverService, times(2))
                    .requestHandoverForWithdrawal(any(), anyLong());
            verify(billingPayerHandoverService).requestHandoverForWithdrawal(ok, USER_ID);
        }

        @Test
        @DisplayName("異常系: 引継対象の検出自体が失敗しても例外は伝播しない")
        void 異常_検出失敗でも例外を伝播しない() {
            given(billingContractService.findHandoverTargetContractsForPayer(anyLong()))
                    .willThrow(new IllegalStateException("DB 障害"));

            assertThatCode(() -> handler.handleWithdrawal(event())).doesNotThrowAnyException();
            verify(billingPayerHandoverService, never())
                    .requestHandoverForWithdrawal(any(), anyLong());
        }
    }

    @Nested
    @DisplayName("③ 退会取消時の復旧（設計書 §6.1・Codex 検分1巡目 P1-3）")
    class WithdrawalCancellation {

        private static WithdrawalCancelledEvent cancelledEvent() {
            return new WithdrawalCancelledEvent(USER_ID);
        }

        @Test
        @DisplayName("正常系: 期末解約の予約解除と引継要求の終端化が両方呼ばれる")
        void 正常_2系統の復旧が呼ばれる() {
            given(membershipSubscriptionService.restoreAllForPayerOnWithdrawalCancelled(anyLong()))
                    .willReturn(List.of(UUID.randomUUID()));
            given(billingPayerHandoverService.failRequestedHandoversOnWithdrawalCancelled(anyLong()))
                    .willReturn(1);

            handler.handleWithdrawalCancelled(cancelledEvent());

            verify(membershipSubscriptionService).restoreAllForPayerOnWithdrawalCancelled(USER_ID);
            verify(billingPayerHandoverService).failRequestedHandoversOnWithdrawalCancelled(USER_ID);
        }

        @Test
        @DisplayName("異常系: 予約解除が失敗しても引継要求の終端化は実行され、例外は伝播しない")
        void 異常_片方の失敗でもう片方を止めない() {
            given(membershipSubscriptionService.restoreAllForPayerOnWithdrawalCancelled(anyLong()))
                    .willThrow(new IllegalStateException("Stripe 障害"));

            assertThatCode(() -> handler.handleWithdrawalCancelled(cancelledEvent()))
                    .doesNotThrowAnyException();
            verify(billingPayerHandoverService).failRequestedHandoversOnWithdrawalCancelled(USER_ID);
        }
    }
}
