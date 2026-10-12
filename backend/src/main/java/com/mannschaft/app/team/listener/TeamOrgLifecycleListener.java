package com.mannschaft.app.team.listener;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.organization.event.OrganizationArchivedEvent;
import com.mannschaft.app.organization.event.OrganizationDeletedEvent;
import com.mannschaft.app.team.event.TeamDeletedEvent;
import com.mannschaft.app.team.service.TeamAffiliationOrganizationPort;
import com.mannschaft.app.team.service.TeamOrgLifecycleCleanupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Set;

/**
 * 組織・チームのアーカイブ／削除に伴う、加盟の片付けを行うリスナー（F01.2.1 §4.5・§6.9）。
 *
 * <ul>
 *   <li>{@link OrganizationArchivedEvent}（AFTER_COMMIT）: 当該組織の PENDING（申請・招待）を削除し、
 *       残った側（チームの加盟操作者）へ {@code TEAM_ORG_PENDING_CANCELLED_BY_SYSTEM} を予約する。ACTIVE は残す</li>
 *   <li>{@link TeamDeletedEvent}（AFTER_COMMIT）: 当該チームの PENDING・ACTIVE・制限を削除し、
 *       PENDING の相手側（組織 ADMIN）へ取消を予約する</li>
 *   <li>{@link OrganizationDeletedEvent}（AFTER_COMMIT）: 当該組織の制限を物理削除し、PENDING を削除して
 *       相手側（チームの加盟操作者）へ取消を予約する</li>
 * </ul>
 *
 * <p>削除と通知の予約は {@link TeamOrgLifecycleCleanupService} の1つのトランザクション（team ドメイン）で行う。
 * 通知の文面に使う組織名は、トランザクションの前に組織ドメインの読み取り（ポート）で引く。
 * チームのアーカイブは {@code TeamService#archiveTeam} が同じトランザクションで片付けるので、ここでは扱わない。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TeamOrgLifecycleListener {

    private final TeamOrgLifecycleCleanupService cleanupService;
    private final TeamAffiliationOrganizationPort organizationPort;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めるとアーカイブ済み組織に PENDING が残り、チームの同時申請枠を埋め続ける。取りこぼしは 7-A の TeamOrgLifecycleCleanupBatch が拾う")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrganizationArchived(OrganizationArchivedEvent event) {
        Long organizationId = event.getOrganizationId();
        String organizationName = organizationPort.findOrganizationRefs(Set.of(organizationId))
                .values().stream().findFirst().map(TeamAffiliationOrganizationPort.OrganizationRef::name).orElse(null);
        int deleted = cleanupService.cancelPendingOfArchivedOrganization(organizationId, organizationName);
        log.info("組織アーカイブに伴う PENDING の片付け: organizationId={}, deleted={}", organizationId, deleted);
    }

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めると削除済みチームの加盟・制限が残り、相手側の申請一覧に残り続ける。取りこぼしは 7-A の TeamOrgLifecycleCleanupBatch が拾う")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTeamDeleted(TeamDeletedEvent event) {
        cleanupService.cleanUpDeletedTeam(event.getTeamId());
    }

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めると削除済み組織の制限・PENDING が残り、チームの同時申請枠を埋め続ける。取りこぼしは 7-A の TeamOrgLifecycleCleanupBatch が拾う")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrganizationDeleted(OrganizationDeletedEvent event) {
        Long organizationId = event.getOrganizationId();
        String organizationName = organizationPort.findOrganizationRefIncludingDeleted(organizationId)
                .map(TeamAffiliationOrganizationPort.OrganizationRef::name).orElse(null);
        cleanupService.cleanUpDeletedOrganization(organizationId, organizationName);
    }
}
