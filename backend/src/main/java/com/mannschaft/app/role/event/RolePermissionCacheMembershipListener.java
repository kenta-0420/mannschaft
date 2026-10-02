package com.mannschaft.app.role.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.role.service.RolePermissionCacheGenerationService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 所属・ロール変更と同じトランザクションで認可キャッシュ世代を進める。 */
@Component
@RequiredArgsConstructor
public class RolePermissionCacheMembershipListener {

    private final RolePermissionCacheGenerationService generationService;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "所属・ロール変更と認可キャッシュ世代更新の原子性を維持するため停止不可")
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onMembershipChanged(MembershipChangedEvent event) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                generationService.incrementGeneration(event.scopeType(), event.scopeId());
            }
        });
    }
}
