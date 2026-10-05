package com.mannschaft.app.team.listener;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.organization.event.OrganizationArchivedEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 組織・チームのアーカイブ／削除に伴う、加盟の片付けを行うリスナー（F01.2.1 §4.5・§6.9）。
 *
 * <p>試練（2-D）の骨格。出陣で次を実装する。</p>
 * <ul>
 *   <li>{@link OrganizationArchivedEvent}（AFTER_COMMIT）: 当該組織の PENDING（申請・招待）を削除し、
 *       残った側（チームの加盟操作者）へ {@code TEAM_ORG_PENDING_CANCELLED_BY_SYSTEM} を enqueue する。ACTIVE は残す</li>
 *   <li>{@code TeamDeletedEvent}（AFTER_COMMIT）: 当該チームの PENDING・ACTIVE・制限を削除し、
 *       PENDING の相手側（組織 ADMIN）へ取消を通知する</li>
 *   <li>{@code OrganizationDeletedEvent}（AFTER_COMMIT）: 当該組織の制限を物理削除し、
 *       PENDING の相手側（チームの加盟操作者）へ取消を通知する</li>
 * </ul>
 */
@Component
public class TeamOrgLifecycleListener {

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めるとアーカイブ済み組織に PENDING が残り、チームの同時申請枠を埋め続ける。取りこぼしは 7-A の TeamOrgLifecycleCleanupBatch が拾う")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrganizationArchived(OrganizationArchivedEvent event) {
        throw new UnsupportedOperationException("2-D 出陣で実装");
    }
}
