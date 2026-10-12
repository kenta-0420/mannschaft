package com.mannschaft.app.team.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * チームのアーカイブ解除イベント（F01.2.1 2-D・§4.5）。
 *
 * <p>他ドメインのトランザクション（システム管理者の強制解除 {@code SystemAdminScopeForceUnarchiveController} など）から
 * チームのアーカイブを解除したときに発行し、team ドメインがコミット後に<b>自分のトランザクションで</b>、チームの最後の閉鎖
 * （{@code teams.last_closed_at}）より前に作られた PENDING を片付ける（{@code TeamOrgLifecycleListener}。解除で復元しない）。
 * 他ドメインのトランザクションに加盟の書き込みを持ち込まないため（CLAUDE.md 原則5・D-3T）。チーム自身の解除 API は
 * 同じトランザクションで片付けるので、このイベントを発行しない。リスナーの取りこぼしは定期バッチ
 * {@code TeamOrgLifecycleCleanupBatch} が状態から拾い直し、片付けまでの間の承認・承諾は閉鎖前の PENDING として拒む。</p>
 */
@Getter
public class TeamUnarchivedEvent extends BaseEvent {

    /** アーカイブを解除したチームID */
    private final Long teamId;

    public TeamUnarchivedEvent(Long teamId) {
        super();
        this.teamId = teamId;
    }
}
