package com.mannschaft.app.team.listener;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.organization.teamgroup.event.OrgTeamGroupDeletedEvent;
import com.mannschaft.app.team.service.TeamOrgGroupUnassignService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * チームグループ削除後に、所属チームの {@code group_id} を未分類へ戻すリスナー（F01.2.1 §7.3）。
 *
 * <p>グループ削除のトランザクションがコミットされた後（{@code AFTER_COMMIT}）に非同期で実行する。
 * organization ドメインのトランザクションに team ドメインの表の更新を巻き込まないための分離
 * （CLAUDE.md 原則 5）。例外は握りつぶさず非同期例外ハンドラに任せる。失敗して残った {@code group_id} は
 * 修復バッチ（7-A）が NULL に戻し、それまでも読み手が削除済みグループを未分類として扱う。</p>
 */
@Component
@RequiredArgsConstructor
public class TeamOrgGroupUnassignListener {

    private final TeamOrgGroupUnassignService unassignService;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めると削除済みグループを指す group_id が加盟行に残り続ける。読み手は未分類として扱うため表示は壊れないが、イベントは再生されないので修復は 7-A の孤児修復バッチに頼ることになる")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrgTeamGroupDeleted(OrgTeamGroupDeletedEvent event) {
        unassignService.unassignGroup(event.getOrganizationId(), event.getGroupId());
    }
}
