package com.mannschaft.app.payment.escrow;

import com.mannschaft.app.recruitment.event.RecruitmentCancelledEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RecruitmentChargeCancellationListenerTest {

    @Mock private EscrowTransactionRepository escrowTransactionRepository;
    @Mock private EscrowLifecycleService escrowLifecycleService;

    @Test
    void paidListing_cancelsEveryLinkedEscrow() {
        UUID firstId = UUID.fromString("019607a0-0000-7000-8000-0000000000a1");
        UUID secondId = UUID.fromString("019607a0-0000-7000-8000-0000000000a2");
        EscrowTransactionEntity first = EscrowTransactionEntity.builder().status(EscrowStatus.AUTHORIZED).build();
        EscrowTransactionEntity second = EscrowTransactionEntity.builder().status(EscrowStatus.HELD).build();
        first.setId(firstId);
        second.setId(secondId);
        given(escrowTransactionRepository.findBySourceKindAndSourceId(EscrowSourceKind.RECRUITMENT, 42L))
                .willReturn(List.of(first, second));
        given(escrowLifecycleService.cancelForRecruitmentCancellation(firstId)).willReturn(true);
        given(escrowLifecycleService.cancelForRecruitmentCancellation(secondId)).willReturn(true);

        new RecruitmentChargeCancellationListener(escrowTransactionRepository, escrowLifecycleService)
                .onRecruitmentCancelled(new RecruitmentCancelledEvent(42L, true));

        verify(escrowLifecycleService).cancelForRecruitmentCancellation(firstId);
        verify(escrowLifecycleService).cancelForRecruitmentCancellation(secondId);
    }

    @Test
    void freeListing_doesNotQueryEscrow() {
        new RecruitmentChargeCancellationListener(escrowTransactionRepository, escrowLifecycleService)
                .onRecruitmentCancelled(new RecruitmentCancelledEvent(42L, false));

        verify(escrowTransactionRepository, never()).findBySourceKindAndSourceId(
                EscrowSourceKind.RECRUITMENT, 42L);
    }
}
