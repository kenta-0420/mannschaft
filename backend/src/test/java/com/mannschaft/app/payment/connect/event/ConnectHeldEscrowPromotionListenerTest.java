package com.mannschaft.app.payment.connect.event;

import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.escrow.EscrowCaptureMode;
import com.mannschaft.app.payment.escrow.EscrowLifecycleService;
import com.mannschaft.app.payment.escrow.EscrowSourceKind;
import com.mannschaft.app.payment.escrow.EscrowStatus;
import com.mannschaft.app.payment.escrow.EscrowTransactionEntity;
import com.mannschaft.app.payment.escrow.EscrowTransactionRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * HELD escrow 昇格リスナーの単体テスト（Issue #2990 L3）。
 *
 * <p>{@code ConnectAccountService} から AFTER_COMMIT リスナーへ移設した昇格ループの挙動
 * （全件委譲・個別失敗分離・対象なしの早期 return）を据え置きで固定する。
 * 「commit の後にしか走らない」という因果順序そのものは、モックでは表現できないため
 * {@code ConnectHeldEscrowPromotionOrderingIT}（実DB）が検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConnectHeldEscrowPromotionListener 単体テスト")
class ConnectHeldEscrowPromotionListenerTest {

    @Mock private EscrowTransactionRepository escrowTransactionRepository;
    @Mock private EscrowLifecycleService escrowLifecycleService;

    @InjectMocks private ConnectHeldEscrowPromotionListener listener;

    @Test
    @DisplayName("HELD escrow を各件 promoteHeldEscrow へ委譲する")
    void promotesEveryHeldEscrow() {
        UUID acctId = UUID.fromString("019607a0-0000-7000-8000-0000000000c1");
        UUID e1 = UUID.randomUUID();
        UUID e2 = UUID.randomUUID();
        given(escrowTransactionRepository.findByPayeeConnectAccountIdAndStatus(
                eq(acctId), eq(EscrowStatus.HELD)))
                .willReturn(List.of(heldEscrow(e1, acctId), heldEscrow(e2, acctId)));

        listener.onConnectPayoutsEnabled(new ConnectPayoutsEnabledEvent(acctId));

        verify(escrowLifecycleService).promoteHeldEscrow(e1);
        verify(escrowLifecycleService).promoteHeldEscrow(e2);
    }

    @Test
    @DisplayName("1 件の昇格失敗でも他件の昇格は継続する（個別失敗分離・握りつぶさない）")
    void promotionFailureDoesNotStopOthers() {
        UUID acctId = UUID.fromString("019607a0-0000-7000-8000-0000000000c2");
        UUID bad = UUID.randomUUID();
        UUID good = UUID.randomUUID();
        given(escrowTransactionRepository.findByPayeeConnectAccountIdAndStatus(
                eq(acctId), eq(EscrowStatus.HELD)))
                .willReturn(List.of(heldEscrow(bad, acctId), heldEscrow(good, acctId)));
        doThrow(new RuntimeException("stripe down"))
                .when(escrowLifecycleService).promoteHeldEscrow(bad);

        listener.onConnectPayoutsEnabled(new ConnectPayoutsEnabledEvent(acctId));

        verify(escrowLifecycleService).promoteHeldEscrow(good);
    }

    @Test
    @DisplayName("HELD escrow が無ければ昇格を委譲しない")
    void noHeldEscrowMeansNoPromotion() {
        UUID acctId = UUID.fromString("019607a0-0000-7000-8000-0000000000c3");
        given(escrowTransactionRepository.findByPayeeConnectAccountIdAndStatus(
                eq(acctId), eq(EscrowStatus.HELD)))
                .willReturn(List.of());

        listener.onConnectPayoutsEnabled(new ConnectPayoutsEnabledEvent(acctId));

        verify(escrowLifecycleService, never()).promoteHeldEscrow(any());
    }

    private EscrowTransactionEntity heldEscrow(UUID id, UUID payeeAccountId) {
        EscrowTransactionEntity e = EscrowTransactionEntity.builder()
                .sourceKind(EscrowSourceKind.RECRUITMENT)
                .sourceId(1L).sourceParticipantId(2L)
                .captureMode(EscrowCaptureMode.MANUAL)
                .payerScopeKind(ScopeKind.USER).payerScopeId(9L)
                .payeeKind(ScopeKind.TEAM).payeeConnectAccountId(payeeAccountId)
                .faceAmount(10_000L).amount(10_250L).applicationFeeAmount(500L)
                .currency("JPY").status(EscrowStatus.HELD)
                .build();
        e.setId(id);
        return e;
    }
}
