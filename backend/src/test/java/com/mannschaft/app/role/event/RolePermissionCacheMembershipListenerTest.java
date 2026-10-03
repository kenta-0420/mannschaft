package com.mannschaft.app.role.event;

import com.mannschaft.app.role.service.RolePermissionCacheGenerationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RolePermissionCacheMembershipListenerTest {

    @Mock
    private RolePermissionCacheGenerationService generationService;

    @Test
    void 所属変更スコープの世代を同一トランザクションで進める() {
        RolePermissionCacheMembershipListener listener =
                new RolePermissionCacheMembershipListener(generationService);

        TransactionSynchronizationManager.initSynchronization();
        try {
            listener.onMembershipChanged(new MembershipChangedEvent(
                    42L, "TEAM", 7L, MembershipChangedEvent.ChangeType.CHANGED));

            for (TransactionSynchronization synchronization
                    : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.beforeCommit(false);
            }
            verify(generationService).incrementGeneration("TEAM", 7L);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
