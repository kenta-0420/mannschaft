package com.mannschaft.app.payment.escrow;

import com.mannschaft.app.payment.escrow.event.EscrowCapturedEvent;
import com.mannschaft.app.payment.service.MemberPaymentService;
import com.mannschaft.app.payment.service.MembershipSubscriptionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F08.9 P1 Wave4 (T8) / P5 第三波: {@link MembershipPaymentCaptureListener} の単体テスト。
 *
 * <p>CAPTURED イベント（sourceKind=MEMBERSHIP）→ PAID 反映の委譲、会費以外（RECRUITMENT）は無視、
 * および継続課金由来（subscription ID 戻り）の PENDING→ACTIVE 活性化委譲を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MembershipPaymentCaptureListener 単体テスト (T8 / P5-3)")
class MembershipPaymentCaptureListenerTest {

    @Mock private MemberPaymentService memberPaymentService;
    @Mock private MembershipSubscriptionService membershipSubscriptionService;

    @InjectMocks
    private MembershipPaymentCaptureListener listener;

    @Test
    @DisplayName("正常系: MEMBERSHIP の CAPTURED で PAID 反映を委譲（単発＝subscription 連結なしは活性化しない）")
    void 会費は反映を委譲() {
        UUID escrowId = UUID.randomUUID();
        // 単発会費は subscription 連結なし（null 戻り）。
        given(memberPaymentService.applyMembershipPaidByEscrow(escrowId)).willReturn(null);

        listener.onEscrowCaptured(new EscrowCapturedEvent(escrowId, EscrowSourceKind.MEMBERSHIP));

        verify(memberPaymentService).applyMembershipPaidByEscrow(escrowId);
        verify(membershipSubscriptionService, never()).activateOnInitialChargeIfPending(any());
    }

    @Test
    @DisplayName("P5-3: 継続課金由来（subscription ID 戻り）は PENDING→ACTIVE 活性化を委譲（唯一の活性化点）")
    void 継続課金は活性化を委譲() {
        UUID escrowId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        given(memberPaymentService.applyMembershipPaidByEscrow(escrowId)).willReturn(subscriptionId);

        listener.onEscrowCaptured(new EscrowCapturedEvent(escrowId, EscrowSourceKind.MEMBERSHIP));

        verify(memberPaymentService).applyMembershipPaidByEscrow(escrowId);
        verify(membershipSubscriptionService).activateOnInitialChargeIfPending(subscriptionId);
    }

    @Test
    @DisplayName("無視: 会費以外(RECRUITMENT)は委譲しない")
    void 謝礼は無視() {
        UUID escrowId = UUID.randomUUID();
        listener.onEscrowCaptured(new EscrowCapturedEvent(escrowId, EscrowSourceKind.RECRUITMENT));
        verify(memberPaymentService, never()).applyMembershipPaidByEscrow(escrowId);
        verify(membershipSubscriptionService, never()).activateOnInitialChargeIfPending(any());
    }
}
