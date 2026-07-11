package com.mannschaft.app.billing;

import com.mannschaft.app.auth.event.WithdrawalCancelledEvent;
import com.mannschaft.app.auth.event.WithdrawalRequestedEvent;
import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import com.mannschaft.app.gdpr.service.AccountPurgeCompletionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * F20.1 実決済: {@link BillingPurgeEventListener} 単体テスト（AC-45・検分差し戻し2番）。
 *
 * <p>purge 確定で「DB 遷移（REQUIRES_NEW・全解約/revoke）→ Stripe サブスク<b>即時解約</b>」が走ること、
 * 申請（猶予中）・撤回は明示 no-op であること、Stripe 失敗が他契約の解約を妨げないことを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BillingPurgeEventListener 単体テスト（退会purge連動・AC-45）")
class BillingPurgeEventListenerTest {

    @Mock private BillingContractService billingContractService;
    @Mock private BillingPaymentGateway billingPaymentGateway;
    @Mock private AccountPurgeCompletionService accountPurgeCompletionService;

    @InjectMocks private BillingPurgeEventListener listener;

    @Test
    @DisplayName("AC-45: purge 確定で DB 全解約（サービス委譲）＋有償契約の Stripe サブスク即時解約")
    void ac45_purge_cancelsDbAndStripeImmediately() {
        given(billingContractService.cancelAllUserContractsForPurge(9L))
                .willReturn(List.of("sub_a", "sub_b"));

        listener.onAccountPurged(new AccountPurgedEvent(9L, "hash"));

        // DB 遷移（CANCELLED＋pointer DELETE＋revoke＋evict）はサービスの REQUIRES_NEW tx。
        verify(billingContractService).cancelAllUserContractsForPurge(9L);
        // 有償契約は cancel_at_period_end ではなく即時解約（退会後の課金継続事故防止）。
        verify(billingPaymentGateway).cancelImmediately("sub_a");
        verify(billingPaymentGateway).cancelImmediately("sub_b");
        verify(billingPaymentGateway, never()).cancelAtPeriodEnd(any());
    }

    @Test
    @DisplayName("残債1: DB＋Stripe が両方成功したら completion_status を billing SUCCESS に更新する")
    void 残債1_purge_allSucceeded_marksSuccess() {
        given(billingContractService.cancelAllUserContractsForPurge(9L))
                .willReturn(List.of("sub_a"));

        listener.onAccountPurged(new AccountPurgedEvent(9L, "hash"));

        // ArchUnit D-3 是正: gdpr の Repository 直接更新ではなく Service 経由で報告する。
        verify(accountPurgeCompletionService).markDomainSuccess(9L, "billing");
    }

    @Test
    @DisplayName("AC-45: Stripe 即時解約の失敗は他契約の解約を妨げない（ERROR ログ＋続行・手動照合）")
    void ac45_purge_stripeFailure_continuesOthers() {
        given(billingContractService.cancelAllUserContractsForPurge(9L))
                .willReturn(List.of("sub_fail", "sub_ok"));
        willThrow(new IllegalStateException("stripe down"))
                .given(billingPaymentGateway).cancelImmediately("sub_fail");

        listener.onAccountPurged(new AccountPurgedEvent(9L, "hash"));

        // 1 件目の失敗でも 2 件目は解約される（イベント基盤へ例外を伝播させない）。
        verify(billingPaymentGateway).cancelImmediately("sub_ok");
    }

    @Test
    @DisplayName("残債1: Stripe 解約が 1 件でも失敗したら completion_status は SUCCESS 更新しない（PENDING のまま残しリトライ対象にする）")
    void 残債1_purge_stripeFailure_doesNotMarkSuccess() {
        given(billingContractService.cancelAllUserContractsForPurge(9L))
                .willReturn(List.of("sub_fail"));
        willThrow(new IllegalStateException("stripe down"))
                .given(billingPaymentGateway).cancelImmediately("sub_fail");

        listener.onAccountPurged(new AccountPurgedEvent(9L, "hash"));

        verify(accountPurgeCompletionService, never()).markDomainSuccess(any(), anyString());
    }

    @Test
    @DisplayName("AC-45: DB 全解約の失敗時は Stripe 解約に進まない（ERROR ログ・手動確認）")
    void ac45_purge_dbFailure_skipsStripe() {
        given(billingContractService.cancelAllUserContractsForPurge(9L))
                .willThrow(new IllegalStateException("db down"));

        listener.onAccountPurged(new AccountPurgedEvent(9L, "hash"));

        verifyNoInteractions(billingPaymentGateway);
    }

    @Test
    @DisplayName("残債1: DB 全解約の失敗時は completion_status を更新しない（PENDING のまま残す）")
    void 残債1_purge_dbFailure_doesNotMarkSuccess() {
        given(billingContractService.cancelAllUserContractsForPurge(9L))
                .willThrow(new IllegalStateException("db down"));

        listener.onAccountPurged(new AccountPurgedEvent(9L, "hash"));

        verifyNoInteractions(accountPurgeCompletionService);
    }

    @Test
    @DisplayName("残債1 retryPurge: DB 再解約（冪等・空返却）＋Stripe 未確認解約契約の両方を retry する")
    void 残債1_retryPurge_retriesDbAndPendingStripeCancel() {
        // DB は既に全て CANCELLED 済み（冪等・空返却）。
        given(billingContractService.cancelAllUserContractsForPurge(9L)).willReturn(List.of());
        // 前回 Stripe 解約が失敗した契約が 1 件残っている。
        given(billingContractService.findPurgedPaidSubscriptionRefsPendingStripeCancel(9L))
                .willReturn(List.of("sub_pending"));

        boolean result = listener.retryPurge(9L);

        assertThat(result).isTrue();
        verify(billingPaymentGateway).cancelImmediately("sub_pending");
    }

    @Test
    @DisplayName("残債1 retryPurge: Stripe 解約が失敗したら false を返す（PENDING 継続・GdprPurgeRetryService が反映）")
    void 残債1_retryPurge_stripeFailure_returnsFalse() {
        given(billingContractService.cancelAllUserContractsForPurge(9L)).willReturn(List.of());
        given(billingContractService.findPurgedPaidSubscriptionRefsPendingStripeCancel(9L))
                .willReturn(List.of("sub_still_fails"));
        willThrow(new IllegalStateException("stripe still down"))
                .given(billingPaymentGateway).cancelImmediately("sub_still_fails");

        boolean result = listener.retryPurge(9L);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("残債1 retryPurge: DB 再解約が失敗したら false を返す")
    void 残債1_retryPurge_dbFailure_returnsFalse() {
        given(billingContractService.cancelAllUserContractsForPurge(9L))
                .willThrow(new IllegalStateException("db still down"));
        given(billingContractService.findPurgedPaidSubscriptionRefsPendingStripeCancel(9L))
                .willReturn(List.of());

        boolean result = listener.retryPurge(9L);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("残債1 retryPurge: DB・Stripe 未確認解約とも対象なしなら true（何もしなくて成功扱い）")
    void 残債1_retryPurge_nothingToDo_returnsTrue() {
        given(billingContractService.cancelAllUserContractsForPurge(9L)).willReturn(List.of());
        given(billingContractService.findPurgedPaidSubscriptionRefsPendingStripeCancel(9L))
                .willReturn(List.of());

        boolean result = listener.retryPurge(9L);

        assertThat(result).isTrue();
        verifyNoInteractions(billingPaymentGateway);
    }

    @Test
    @DisplayName("AC-45: 退会申請（猶予開始）は明示 no-op（契約・権利を維持＝撤回で復活不可な revoke をしない）")
    void ac45_withdrawalRequested_noop() {
        listener.onWithdrawalRequested(new WithdrawalRequestedEvent(9L, "a@example.com"));

        verifyNoInteractions(billingContractService, billingPaymentGateway);
    }

    @Test
    @DisplayName("AC-45: 退会撤回は明示 no-op（権利維持のまま）")
    void ac45_withdrawalCancelled_noop() {
        listener.onWithdrawalCancelled(new WithdrawalCancelledEvent(9L));

        verifyNoInteractions(billingContractService, billingPaymentGateway);
    }
}
