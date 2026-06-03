package com.mannschaft.app.payment.escrow;

import com.mannschaft.app.payment.escrow.event.EscrowCapturedEvent;
import com.mannschaft.app.payment.service.MemberPaymentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F08.9 P1 Wave4 (T8): {@link MembershipPaymentCaptureListener} の単体テスト。
 *
 * <p>CAPTURED イベント（sourceKind=MEMBERSHIP）→ PAID 反映の委譲、会費以外（RECRUITMENT）は無視を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MembershipPaymentCaptureListener 単体テスト (T8)")
class MembershipPaymentCaptureListenerTest {

    @Mock private MemberPaymentService memberPaymentService;

    @InjectMocks
    private MembershipPaymentCaptureListener listener;

    @Test
    @DisplayName("正常系: MEMBERSHIP の CAPTURED で PAID 反映を委譲")
    void 会費は反映を委譲() {
        UUID escrowId = UUID.randomUUID();
        listener.onEscrowCaptured(new EscrowCapturedEvent(escrowId, EscrowSourceKind.MEMBERSHIP));
        verify(memberPaymentService).applyMembershipPaidByEscrow(escrowId);
    }

    @Test
    @DisplayName("無視: 会費以外(RECRUITMENT)は委譲しない")
    void 謝礼は無視() {
        UUID escrowId = UUID.randomUUID();
        listener.onEscrowCaptured(new EscrowCapturedEvent(escrowId, EscrowSourceKind.RECRUITMENT));
        verify(memberPaymentService, never()).applyMembershipPaidByEscrow(escrowId);
    }
}
