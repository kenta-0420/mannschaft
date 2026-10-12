package com.mannschaft.app.team.listener;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.organization.event.OrganizationArchivedEvent;
import com.mannschaft.app.organization.event.OrganizationDeletedEvent;
import com.mannschaft.app.team.event.TeamArchivedEvent;
import com.mannschaft.app.team.event.TeamDeletedEvent;
import com.mannschaft.app.team.service.TeamOrgLifecycleCleanupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 組織・チームのアーカイブ／削除に伴う加盟の片付けの<b>速い経路</b>（F01.2.1 §4.5・§6.9・2-D）。
 *
 * <p>契機のコミット後（AFTER_COMMIT・非同期）に、{@link TeamOrgLifecycleCleanupService} の<b>状態に基づく</b>片付けを呼ぶ。
 * 片付けは相手の行をロックして現在の状態を確かめてから消すので、イベントが古くても（アーカイブ解除の後に届いても）
 * 解除後の申請・招待を消さない。リスナーの前のプロセス停止・片付けや outbox の書き込みの失敗で取りこぼしても、
 * 回復の経路である定期バッチ {@code TeamOrgLifecycleCleanupBatch} が同じ片付けを状態から呼び直す。</p>
 *
 * <ul>
 *   <li>{@link OrganizationArchivedEvent}・{@link OrganizationDeletedEvent} → {@link TeamOrgLifecycleCleanupService#cleanUpOrganization}</li>
 *   <li>{@link TeamDeletedEvent}・{@link TeamArchivedEvent}（他ドメインの tx からのアーカイブ）→ {@link TeamOrgLifecycleCleanupService#cleanUpTeam}</li>
 * </ul>
 * <p>チーム自身のアーカイブ API（{@code TeamService#archiveTeam}）は同じトランザクションで片付けるので、ここでは扱わない。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TeamOrgLifecycleListener {

    private final TeamOrgLifecycleCleanupService cleanupService;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めるとアーカイブ済み組織に PENDING が残り、チームの同時申請枠を埋め続ける。取りこぼしは TeamOrgLifecycleCleanupBatch が拾う")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrganizationArchived(OrganizationArchivedEvent event) {
        int deleted = cleanupService.cleanUpOrganization(event.getOrganizationId());
        log.info("組織アーカイブに伴う PENDING の片付け: organizationId={}, deleted={}", event.getOrganizationId(), deleted);
    }

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めると削除済み組織の制限・PENDING が残り、チームの同時申請枠を埋め続ける。取りこぼしは TeamOrgLifecycleCleanupBatch が拾う")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrganizationDeleted(OrganizationDeletedEvent event) {
        cleanupService.cleanUpOrganization(event.getOrganizationId());
    }

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めると削除済みチームの加盟・制限が残り、相手側の申請一覧に残り続ける。取りこぼしは TeamOrgLifecycleCleanupBatch が拾う")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTeamDeleted(TeamDeletedEvent event) {
        cleanupService.cleanUpTeam(event.getTeamId());
    }

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めると他ドメインの tx からアーカイブされたチームの PENDING が残る。取りこぼしは TeamOrgLifecycleCleanupBatch が拾う")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTeamArchived(TeamArchivedEvent event) {
        cleanupService.cleanUpTeam(event.getTeamId());
    }
}
