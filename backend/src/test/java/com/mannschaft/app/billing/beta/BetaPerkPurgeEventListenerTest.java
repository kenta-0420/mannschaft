package com.mannschaft.app.billing.beta;

import com.mannschaft.app.auth.event.WithdrawalCancelledEvent;
import com.mannschaft.app.auth.event.WithdrawalRequestedEvent;
import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link BetaPerkPurgeEventListener} 単体テスト（F20.3 退会連動・試練先行）。
 *
 * <p>受け入れ条件 AC-A8: {@code AccountPurgedEvent} で {@code revokeAllForUser(WITHDRAWAL)} を呼ぶ。
 * 退会申請({@code WithdrawalRequestedEvent})・撤回({@code WithdrawalCancelledEvent})は no-op（grant を変えない）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BetaPerkPurgeEventListener 単体テスト（退会 purge 連動）")
class BetaPerkPurgeEventListenerTest {

    @Mock private BetaGrantService betaGrantService;

    @InjectMocks private BetaPerkPurgeEventListener listener;

    @BeforeEach
    void setUp() {
        // no-op（@InjectMocks が listener を組み立てる）。
    }

    @Test
    @DisplayName("AC-A8: AccountPurgedEvent(確定) → revokeAllForUser(userId, WITHDRAWAL)")
    void onAccountPurged_revokesWithWithdrawalReason() {
        listener.onAccountPurged(new AccountPurgedEvent(42L, "hash"));

        verify(betaGrantService).revokeAllForUser(42L, BetaRevokeReason.WITHDRAWAL);
    }

    @Test
    @DisplayName("退会申請(WithdrawalRequestedEvent) は no-op（grant を変えない）")
    void onWithdrawalRequested_noop() {
        listener.onWithdrawalRequested(new WithdrawalRequestedEvent(42L, "a@example.com"));

        verifyNoInteractions(betaGrantService);
    }

    @Test
    @DisplayName("退会撤回(WithdrawalCancelledEvent) は no-op（grant を維持）")
    void onWithdrawalCancelled_noop() {
        listener.onWithdrawalCancelled(new WithdrawalCancelledEvent(42L));

        verifyNoInteractions(betaGrantService);
    }
}
